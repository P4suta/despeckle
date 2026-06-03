package io.github.p4suta.despeckle.testsupport;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
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

/**
 * PDFBox-backed PDF fixtures, published from {@code :infrastructure} test-fixtures so the
 * cross-module tests (notably {@code :app}'s end-to-end pipeline tests) can build and inspect real
 * PDFs without putting PDFBox on their own classpath — keeping the PDF library confined to {@code
 * :infrastructure} the same way production code does.
 */
public final class TestPdfs {

    private TestPdfs() {}

    /**
     * Write a few-page bitonal PDF whose pages carry a kept glyph-sized block plus isolated dust
     * the despeckle filter removes. Each page is sized so {@code pdfimages} reports ~300 x-ppi.
     *
     * @param path the PDF to write
     * @param pages the number of identical pages to emit
     * @throws IOException if the PDF cannot be written
     */
    public static void writeBitonalPdf(Path path, int pages) throws IOException {
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

    /**
     * The page count of a PDF.
     *
     * @param pdf the PDF to read
     * @return the number of pages
     * @throws IOException if the PDF cannot be read
     */
    public static int pageCount(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }

    /**
     * Whether the first image XObject on the first page is encoded with the {@code /JBIG2Decode}
     * filter — i.e. the page was packed as lossless JBIG2.
     *
     * @param pdf the PDF to inspect
     * @return {@code true} if the first page's first image is JBIG2-encoded
     * @throws IOException if the PDF cannot be read
     */
    public static boolean firstImageIsJbig2(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
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
}
