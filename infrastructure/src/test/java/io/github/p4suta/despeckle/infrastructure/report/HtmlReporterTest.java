package io.github.p4suta.despeckle.infrastructure.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.p4suta.despeckle.domain.model.ProcessResult;
import io.github.p4suta.despeckle.port.Reporter;
import io.github.p4suta.despeckle.testsupport.TestImages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the report renderer end to end through the {@link HtmlReporterFactory} → {@link
 * Reporter} port: builds the before/overlay/after panels from real bitonal pages (so the Leptonica
 * re-read and the AWT overlay pass both run) and the corpus artifacts + {@code index.html} at
 * finish.
 */
final class HtmlReporterTest {

    // 10% black removed → over the warn threshold; and 1% → under it, covering both row stylings.
    private static final ProcessResult HEAVY = new ProcessResult(20, 10, 100, 90);
    private static final ProcessResult LIGHT = new ProcessResult(20, 19, 100, 99);

    @Test
    void writesPanelsAndIndex(@TempDir Path tmp) throws IOException {
        Path report = tmp.resolve("report");
        Path in = writePage(tmp.resolve("in.pbm"), true);
        Path out = writePage(tmp.resolve("out.pbm"), false);

        Reporter reporter = new HtmlReporterFactory().create(report, false);
        reporter.addPage(Path.of("page-01"), in, out, HEAVY);
        reporter.addPage(Path.of("page-02"), in, out, LIGHT);
        reporter.finish();

        Path index = report.resolve("index.html");
        assertTrue(Files.exists(index), "index.html is written");
        String html = Files.readString(index, StandardCharsets.UTF_8);
        assertTrue(html.contains("despeckle report"), "the report header is present");
        assertTrue(html.contains("class=\"warn\""), "the heavy page is flagged as over-removal");
        assertTrue(panelExists(report.resolve("before"), "page-01"), "a before panel is written");
        assertTrue(
                panelExists(report.resolve("overlay"), "page-01"), "an overlay panel is written");
        assertTrue(
                corpusArtifactExists(report, "removed-heatmap"), "the corpus heatmap is written");
    }

    /**
     * A 64×64 page: a kept glyph block, plus (when {@code withDust}) an isolated dot the clean
     * drops.
     */
    private static Path writePage(Path path, boolean withDust) throws IOException {
        boolean[][] img = TestImages.blank(64, 64);
        TestImages.fillRect(img, 10, 10, 40, 40);
        if (withDust) {
            TestImages.dot(img, 55, 55);
        }
        TestImages.writePbm(path, img);
        return path;
    }

    private static boolean panelExists(Path panelDir, String stem) {
        return Files.exists(panelDir.resolve(stem + ".webp"))
                || Files.exists(panelDir.resolve(stem + ".png"));
    }

    private static boolean corpusArtifactExists(Path report, String basename) {
        return Files.exists(report.resolve(basename + ".webp"))
                || Files.exists(report.resolve(basename + ".png"));
    }
}
