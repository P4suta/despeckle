package io.github.p4suta.despeckle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.p4suta.despeckle.TestImages;
import io.github.p4suta.despeckle.core.OutputFormat;
import io.github.p4suta.despeckle.core.ProcessOptions;
import io.github.p4suta.despeckle.runner.Runner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end directory run: every input page gets a same-named output. Mirrors the intent of the
 * old Rust {@code e2e_dir.rs} integration test.
 */
final class MainE2eTest {

    @Test
    void directoryRunMirrorsEveryPage(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("input");
        Path output = tmp.resolve("output");
        Files.createDirectories(input);

        for (int i = 1; i <= 3; i++) {
            boolean[][] img = TestImages.blank(24, 24);
            TestImages.fillRect(img, 4, 4, 15, 19);
            TestImages.dot(img, 1, 1);
            TestImages.writePbm(input.resolve("page-%02d.pbm".formatted(i)), img);
        }

        Runner.Config config =
                new Runner.Config(
                        input,
                        output,
                        OutputFormat.SAME,
                        "*.{pbm,png,tiff,tif}",
                        2,
                        true,
                        ProcessOptions.of(OptionalInt.of(300), OptionalInt.of(3), true),
                        null,
                        false);
        Runner.Summary summary = new Runner().run(config);

        assertEquals(3, summary.pages());
        try (Stream<Path> entries = Files.list(output)) {
            List<String> names =
                    entries.map(
                                    p -> {
                                        // Path.getFileName() is nullable (a root has none); mirror
                                        // the production guards in Runner rather than assume.
                                        Path name = p.getFileName();
                                        return name == null ? p.toString() : name.toString();
                                    })
                            .sorted()
                            .toList();
            assertEquals(List.of("page-01.pbm", "page-02.pbm", "page-03.pbm"), names);
        }
    }

    @Test
    void reportProducesIndexAndPanels(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Path output = tmp.resolve("out");
        Path report = tmp.resolve("report");
        Files.createDirectories(input);

        boolean[][] img = TestImages.blank(32, 32);
        TestImages.fillRect(img, 6, 6, 17, 25);
        TestImages.dot(img, 28, 3);
        TestImages.writePbm(input.resolve("p1.pbm"), img);

        Runner.Config config =
                new Runner.Config(
                        input,
                        output,
                        OutputFormat.SAME,
                        "*.pbm",
                        1,
                        true,
                        ProcessOptions.defaults(),
                        report,
                        false);
        new Runner().run(config);

        assertTrue(Files.exists(report.resolve("index.html")), "index.html written");
        assertTrue(Files.exists(report.resolve("before/p1.png")), "before panel written");
        assertTrue(Files.exists(report.resolve("overlay/p1.png")), "overlay panel written");
        assertTrue(Files.exists(report.resolve("after/p1.png")), "after panel written");
        // The corpus diagnostics come out as WebP, or fall back to PNG when cwebp is absent.
        assertTrue(artifactExists(report, "removed-heatmap"), "heatmap written");
        assertTrue(artifactExists(report, "corpus-convergence"), "convergence chart written");
        assertTrue(artifactExists(report, "removal-chart"), "removal chart written");
    }

    @Test
    void flipbookDegradesGracefullyWhenToolIsMissing(@TempDir Path tmp) throws Exception {
        Path input = tmp.resolve("in");
        Path output = tmp.resolve("out");
        Path report = tmp.resolve("report");
        Files.createDirectories(input);

        boolean[][] img = TestImages.blank(32, 32);
        TestImages.fillRect(img, 6, 6, 17, 25);
        TestImages.dot(img, 28, 3);
        TestImages.writePbm(input.resolve("p1.pbm"), img);

        // Point img2webp at a binary that cannot exist, so the flip-book always degrades here
        // regardless of whether libwebp is installed on the test host.
        String previous = System.getProperty("despeckle.img2webp.path");
        System.setProperty("despeckle.img2webp.path", tmp.resolve("no-such-img2webp").toString());
        try {
            Runner.Config config =
                    new Runner.Config(
                            input,
                            output,
                            OutputFormat.SAME,
                            "*.pbm",
                            1,
                            true,
                            ProcessOptions.defaults(),
                            report,
                            true);
            Runner.Summary summary = new Runner().run(config);

            assertEquals(1, summary.pages(), "the run still succeeds without img2webp");
            assertTrue(Files.exists(report.resolve("index.html")), "index.html still written");
            assertFalse(
                    Files.exists(report.resolve("flipbook.webp")),
                    "no flip-book when img2webp is unavailable");
        } finally {
            if (previous == null) {
                System.clearProperty("despeckle.img2webp.path");
            } else {
                System.setProperty("despeckle.img2webp.path", previous);
            }
        }
    }

    private static boolean artifactExists(Path dir, String base) {
        return Files.exists(dir.resolve(base + ".webp"))
                || Files.exists(dir.resolve(base + ".png"));
    }
}
