package io.github.p4suta.despeckle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.p4suta.despeckle.TestImages;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end {@code despeckle pipeline} over real, generated bitonal PDFs — guarded so it only runs
 * where the external tools exist (the dev container; skipped on a bare CI runner). Fixtures are
 * built procedurally with PDFBox's {@link CCITTFactory}, so no binary sample is committed.
 */
final class PipelineE2eTest {

    @Test
    void singlePdfProducesAJbig2Pdf(@TempDir Path tmp) throws Exception {
        assumeTrue(toolsPresent(), "pipeline tools (pdfimages/pdfinfo/jbig2) not installed");
        Path in = tmp.resolve("book.pdf");
        Path out = tmp.resolve("book-clean.pdf");
        writeBitonalPdf(in, 3);

        int code = new DespeckleCli().run(new String[] {"pipeline", in.toString(), out.toString()});

        assertEquals(0, code, "the pipeline succeeds");
        assertTrue(Files.exists(out), "the cleaned PDF is written");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages(), "every page round-trips");
            assertTrue(firstImageIsJbig2(doc), "pages are packed as lossless JBIG2");
        }
    }

    @Test
    void batchContinuesOnErrorAndWritesIndex(@TempDir Path tmp) throws Exception {
        assumeTrue(toolsPresent(), "pipeline tools (pdfimages/pdfinfo/jbig2) not installed");
        Path scans = Files.createDirectories(tmp.resolve("scans"));
        Path out = tmp.resolve("out");
        Path reports = tmp.resolve("reports");
        writeBitonalPdf(scans.resolve("a.pdf"), 2);
        writeBitonalPdf(scans.resolve("b.pdf"), 2);
        Files.writeString(scans.resolve("bad.pdf"), "not a pdf", StandardCharsets.UTF_8);

        int code =
                new DespeckleCli()
                        .run(
                                new String[] {
                                    "pipeline",
                                    scans.toString(),
                                    out.toString(),
                                    "--report",
                                    reports.toString(),
                                    "--force"
                                });

        assertEquals(1, code, "a failed book makes the batch exit non-zero");
        assertTrue(Files.exists(out.resolve("a.pdf")), "good book a is cleaned");
        assertTrue(Files.exists(out.resolve("b.pdf")), "good book b is cleaned");
        assertFalse(Files.exists(out.resolve("bad.pdf")), "the corrupt book produces no output");
        String index = Files.readString(reports.resolve("index.html"));
        assertTrue(index.contains("failed"), "the batch index lists the failure");
    }

    @Test
    void topdfPacksAnImageDirIntoAJbig2Pdf(@TempDir Path tmp) throws Exception {
        assumeTrue(toolAvailable("jbig2"), "jbig2 (jbig2enc) not installed");
        Path cleaned = Files.createDirectories(tmp.resolve("cleaned"));
        for (int i = 1; i <= 3; i++) {
            boolean[][] img = TestImages.blank(200, 300);
            TestImages.fillRect(img, 40, 50, 159, 239);
            TestImages.writePbm(cleaned.resolve("page-%02d.pbm".formatted(i)), img);
        }
        Path out = tmp.resolve("book.pdf");

        int code =
                new DespeckleCli()
                        .run(
                                new String[] {
                                    "topdf",
                                    cleaned.toString(),
                                    out.toString(),
                                    "--dpi",
                                    "300",
                                    "--force"
                                });

        assertEquals(0, code, "topdf succeeds");
        assertTrue(Files.exists(out), "the JBIG2 PDF is written");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages(), "every page is packed");
            assertTrue(firstImageIsJbig2(doc), "pages are lossless JBIG2");
        }
    }

    private static boolean firstImageIsJbig2(PDDocument doc) throws IOException {
        PDPage page = doc.getPage(0);
        PDResources resources = page.getResources();
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobject = resources.getXObject(name);
            if (xobject instanceof PDImageXObject image) {
                COSBase filter = image.getCOSObject().getItem(COSName.FILTER);
                if (filter != null && filter.toString().contains("JBIG2Decode")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A few-page bitonal PDF: a kept glyph block plus isolated dust the filter removes. */
    private static void writeBitonalPdf(Path path, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            BufferedImage bitonal = bitonalPage();
            for (int i = 0; i < pages; i++) {
                PDImageXObject image = CCITTFactory.createFromImage(doc, bitonal);
                // Size the page so pdfimages reports ~300 x-ppi (page inches = px / 300).
                float widthPt = bitonal.getWidth() / 300f * 72f;
                float heightPt = bitonal.getHeight() / 300f * 72f;
                PDPage page = new PDPage(new PDRectangle(widthPt, heightPt));
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.drawImage(image, 0, 0, widthPt, heightPt);
                }
            }
            doc.save(path.toFile());
        }
    }

    private static BufferedImage bitonalPage() {
        BufferedImage image = new BufferedImage(200, 300, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 200, 300);
            g.setColor(Color.BLACK);
            g.fillRect(40, 50, 120, 190); // a glyph-sized block — kept
            g.fillRect(8, 8, 1, 1); // isolated dust — removed
            g.fillRect(190, 290, 1, 1);
            g.fillRect(6, 280, 1, 1);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static boolean toolsPresent() {
        return toolAvailable("pdfimages") && toolAvailable("pdfinfo") && toolAvailable("jbig2");
    }

    private static boolean toolAvailable(String tool) {
        try {
            Process process =
                    new ProcessBuilder(tool, "-v")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            process.waitFor();
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
