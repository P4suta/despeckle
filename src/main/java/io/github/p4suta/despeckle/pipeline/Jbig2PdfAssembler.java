package io.github.p4suta.despeckle.pipeline;

import io.github.p4suta.despeckle.core.Pix;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.jspecify.annotations.Nullable;

/**
 * Packs a directory of cleaned bitonal pages into a lossless-JBIG2 PDF — the Java port of {@code
 * jbig2-pdf.py} + {@code pdfmeta.py}. Each page is encoded by {@code jbig2 -p} (jbig2enc's
 * generic-region mode, lossless; never the lossy {@code -s} symbol mode) in parallel, then embedded
 * verbatim as a {@code /JBIG2Decode} image XObject via PDFBox. Because the per-page JBIG2 streams
 * come from the same {@code jbig2} binary the Python pipeline used, the decoded pages are
 * bit-identical; the container is finished with a {@code qpdf --linearize} pass (the caller's) to
 * keep the Fast-Web-View output the Python path produced.
 *
 * <p>Each page is sized by its own resolution (so a mixed-resolution book — e.g. a 600-dpi text
 * with a 1200-dpi plate — comes out correctly), unless the caller forces one DPI; a page that
 * carries no resolution falls back to {@link #DEFAULT_DPI}. This matches {@code jbig2-pdf.py}'s
 * per-image {@code page_dpi}.
 */
final class Jbig2PdfAssembler {

    /** Page size assumed when an image carries no resolution (matches {@code jbig2-pdf.py}). */
    static final int DEFAULT_DPI = 300;

    private static final COSName JBIG2_DECODE = COSName.getPDFName("JBIG2Decode");

    private Jbig2PdfAssembler() {}

    /** A page ready to embed: its lossless JBIG2 stream (on disk), pixel size and its own DPI. */
    private record Page(Path jbig2, int width, int height, int dpi) {}

    /**
     * Assemble {@code imageDir}'s cleaned pages into {@code outPdf}.
     *
     * @param imageDir the directory of cleaned bitonal pages (name order is reading order)
     * @param outPdf the lossless-JBIG2 PDF to write
     * @param source a PDF whose Info dict, XMP and version are inherited, or {@code null} for none
     * @param forcedDpi a single DPI to size every page with, or empty to read each image's own
     * @param pool the worker pool the per-page {@code jbig2} encodes run on
     * @param jb2Dir scratch directory for the intermediate per-page JBIG2 streams (caller-owned)
     * @throws IOException if the directory is empty, a tool fails, or the write fails
     */
    static void assemble(
            Path imageDir,
            Path outPdf,
            @Nullable Path source,
            OptionalInt forcedDpi,
            ExecutorService pool,
            Path jb2Dir)
            throws IOException {
        List<Path> images = sortedImages(imageDir);
        if (images.isEmpty()) {
            throw new IOException("no cleaned images to pack in " + imageDir);
        }
        String jbig2 = NativeTools.jbig2();
        List<Callable<Page>> tasks = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            Path image = images.get(i);
            int index = i;
            tasks.add(() -> encode(jbig2, image, jb2Dir, index, forcedDpi));
        }
        List<Page> pages = NativeTools.awaitAll(pool, tasks);

        try (PDDocument doc = new PDDocument()) {
            for (Page page : pages) {
                addPage(doc, page);
            }
            inheritMetadata(doc, source);
            doc.save(outPdf.toFile());
        }
    }

    /** Encode one page to a lossless JBIG2 stream on disk; safe to run in parallel. */
    private static Page encode(
            String jbig2, Path image, Path jb2Dir, int index, OptionalInt forcedDpi)
            throws IOException {
        int width;
        int height;
        int dpi;
        try (Pix pix = Pix.read(image)) {
            width = pix.width();
            height = pix.height();
            int resolution = pix.resolution();
            dpi = forcedDpi.orElse(resolution > 0 ? resolution : DEFAULT_DPI);
        }
        byte[] stream = NativeTools.capture(List.of(jbig2, "-p", image.toString()), 300);
        Path out = jb2Dir.resolve(String.format(Locale.ROOT, "%06d.jb2", index));
        Files.write(out, stream);
        return new Page(out, width, height, dpi);
    }

    /** Embed one page's JBIG2 stream as a full-page {@code /JBIG2Decode} image XObject. */
    private static void addPage(PDDocument doc, Page page) throws IOException {
        COSStream cos = doc.getDocument().createCOSStream();
        // createRawOutputStream stores the bytes verbatim (no re-filtering) — the analogue of
        // pikepdf's Stream(pdf, data); createOutputStream(JBIG2Decode) would try to *encode*, which
        // PDFBox cannot do for JBIG2.
        try (OutputStream raw = cos.createRawOutputStream();
                InputStream in = Files.newInputStream(page.jbig2())) {
            in.transferTo(raw);
        }
        cos.setItem(COSName.TYPE, COSName.XOBJECT);
        cos.setItem(COSName.SUBTYPE, COSName.IMAGE);
        cos.setInt(COSName.WIDTH, page.width());
        cos.setInt(COSName.HEIGHT, page.height());
        cos.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        cos.setInt(COSName.BITS_PER_COMPONENT, 1);
        cos.setItem(COSName.FILTER, JBIG2_DECODE);
        PDImageXObject image = new PDImageXObject(new PDStream(cos), null);

        float widthPt = points(page.width(), page.dpi());
        float heightPt = points(page.height(), page.dpi());
        PDPage pdPage = new PDPage(new PDRectangle(widthPt, heightPt));
        doc.addPage(pdPage);
        try (PDPageContentStream content = new PDPageContentStream(doc, pdPage)) {
            content.drawImage(image, 0, 0, widthPt, heightPt);
        }
    }

    /** Copy the source PDF's Info dict, XMP metadata and (>= 1.4) version onto the output. */
    private static void inheritMetadata(PDDocument doc, @Nullable Path source) throws IOException {
        if (source == null) {
            // No source scan to mirror (the topdf path may pack loose pages): keep PDFBox's own
            // Info,
            // but still never declare a version below 1.4, which JBIG2Decode requires.
            doc.setVersion(Math.max(doc.getVersion(), 1.4f));
            return;
        }
        try (PDDocument src = Loader.loadPDF(source.toFile())) {
            COSDictionary srcInfo = src.getDocumentInformation().getCOSObject();
            COSDictionary outInfo = doc.getDocumentInformation().getCOSObject();
            for (COSName key : srcInfo.keySet()) {
                outInfo.setItem(key, srcInfo.getItem(key));
            }
            PDMetadata srcMetadata = src.getDocumentCatalog().getMetadata();
            if (srcMetadata != null) {
                byte[] xmp;
                try (InputStream in = srcMetadata.createInputStream()) {
                    xmp = in.readAllBytes();
                }
                PDMetadata outMetadata = new PDMetadata(doc);
                outMetadata.importXMPMetadata(xmp);
                doc.getDocumentCatalog().setMetadata(outMetadata);
            }
            // JBIG2Decode is a PDF 1.4 feature, so never declare a version below 1.4.
            doc.setVersion(Math.max(src.getVersion(), 1.4f));
        }
    }

    /** Page extent in points: pixels / dpi * 72, rounded to 4 dp (matches {@code jbig2-pdf.py}). */
    private static float points(int pixels, int dpi) {
        double pt = (double) pixels / dpi * 72.0;
        return (float) (Math.round(pt * 10_000.0) / 10_000.0);
    }

    private static List<Path> sortedImages(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }
}
