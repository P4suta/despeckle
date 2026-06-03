package io.github.p4suta.despeckle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.p4suta.despeckle.testsupport.TestImages;
import io.github.p4suta.despeckle.testsupport.TestPdfs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end {@code despeckle pipeline} over real, generated bitonal PDFs — guarded so it only runs
 * where the external tools exist (the dev container; skipped on a bare CI runner). Fixtures are
 * built procedurally by {@link TestPdfs} (PDFBox-backed test-fixtures from {@code
 * :infrastructure}), so no binary sample is committed and PDFBox stays off this module's classpath.
 */
final class PipelineE2eTest {

    @Test
    void singlePdfProducesAJbig2Pdf(@TempDir Path tmp) throws Exception {
        assumeTrue(toolsPresent(), "pipeline tools (pdfimages/pdfinfo/jbig2) not installed");
        Path in = tmp.resolve("book.pdf");
        Path out = tmp.resolve("book-clean.pdf");
        TestPdfs.writeBitonalPdf(in, 3);

        int code = new DespeckleCli().run(new String[] {"pipeline", in.toString(), out.toString()});

        assertEquals(0, code, "the pipeline succeeds");
        assertTrue(Files.exists(out), "the cleaned PDF is written");
        assertEquals(3, TestPdfs.pageCount(out), "every page round-trips");
        assertTrue(TestPdfs.firstImageIsJbig2(out), "pages are packed as lossless JBIG2");
    }

    @Test
    void batchContinuesOnErrorAndWritesIndex(@TempDir Path tmp) throws Exception {
        assumeTrue(toolsPresent(), "pipeline tools (pdfimages/pdfinfo/jbig2) not installed");
        Path scans = Files.createDirectories(tmp.resolve("scans"));
        Path out = tmp.resolve("out");
        Path reports = tmp.resolve("reports");
        TestPdfs.writeBitonalPdf(scans.resolve("a.pdf"), 2);
        TestPdfs.writeBitonalPdf(scans.resolve("b.pdf"), 2);
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
        assertEquals(3, TestPdfs.pageCount(out), "every page is packed");
        assertTrue(TestPdfs.firstImageIsJbig2(out), "pages are lossless JBIG2");
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
