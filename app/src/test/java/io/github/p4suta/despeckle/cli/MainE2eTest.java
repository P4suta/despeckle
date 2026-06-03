package io.github.p4suta.despeckle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.p4suta.despeckle.testsupport.TestImages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end directory run driven through the public CLI ({@link DespeckleCli#run(String[])}):
 * every input page gets a same-named output, and {@code --report} writes the index plus the WebP
 * corpus panels. Mirrors the intent of the old Rust {@code e2e_dir.rs} integration test.
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

        // Mirrors the old Runner.Config: SAME format, the default glob, dpi 300, speck 3, the
        // isolated-dust pass off (--no-remove-isolated-dust), hole-filling left on by default.
        int code =
                new DespeckleCli()
                        .run(
                                new String[] {
                                    input.toString(),
                                    output.toString(),
                                    "--jobs",
                                    "2",
                                    "--dpi",
                                    "300",
                                    "--speck-size",
                                    "3",
                                    "--no-remove-isolated-dust",
                                    "--force"
                                });

        assertEquals(0, code, "the directory run succeeds");
        try (Stream<Path> entries = Files.list(output)) {
            List<String> names =
                    entries.map(
                                    p -> {
                                        // Path.getFileName() is nullable (a root has none); mirror
                                        // the production guards rather than assume.
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

        int code =
                new DespeckleCli()
                        .run(
                                new String[] {
                                    input.toString(),
                                    output.toString(),
                                    "--glob",
                                    "*.pbm",
                                    "--jobs",
                                    "1",
                                    "--report",
                                    report.toString(),
                                    "--force"
                                });

        assertEquals(0, code, "the reported run succeeds");
        assertTrue(Files.exists(report.resolve("index.html")), "index.html written");
        // Panels (and corpus artifacts) come out as WebP, or fall back to PNG when cwebp is absent.
        assertTrue(artifactExists(report, "before/p1"), "before panel written");
        assertTrue(artifactExists(report, "overlay/p1"), "overlay panel written");
        assertTrue(artifactExists(report, "after/p1"), "after panel written");
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
            int code =
                    new DespeckleCli()
                            .run(
                                    new String[] {
                                        input.toString(),
                                        output.toString(),
                                        "--glob",
                                        "*.pbm",
                                        "--jobs",
                                        "1",
                                        "--report",
                                        report.toString(),
                                        "--flipbook",
                                        "--force"
                                    });

            assertEquals(0, code, "the run still succeeds without img2webp");
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

    @Test
    void corpusStillsAreWebpWhenCwebpIsAvailable(@TempDir Path tmp) throws Exception {
        assumeTrue(toolAvailable("cwebp"), "cwebp (libwebp) not installed");
        Path input = tmp.resolve("in");
        Path report = tmp.resolve("report");
        Files.createDirectories(input);

        boolean[][] img = TestImages.blank(32, 32);
        TestImages.fillRect(img, 6, 6, 17, 25);
        TestImages.dot(img, 28, 3);
        TestImages.writePbm(input.resolve("p1.pbm"), img);

        int code =
                new DespeckleCli()
                        .run(
                                new String[] {
                                    input.toString(),
                                    tmp.resolve("out").toString(),
                                    "--glob",
                                    "*.pbm",
                                    "--jobs",
                                    "1",
                                    "--report",
                                    report.toString(),
                                    "--force"
                                });

        assertEquals(0, code, "the reported run succeeds");
        // With cwebp present the corpus stills must come out as actual WebP, not the PNG fallback.
        assertTrue(Files.exists(report.resolve("removed-heatmap.webp")), "heatmap is webp");
        assertTrue(Files.exists(report.resolve("corpus-convergence.webp")), "convergence is webp");
        assertTrue(Files.exists(report.resolve("removal-chart.webp")), "removal chart is webp");
        assertFalse(Files.exists(report.resolve("removed-heatmap.png")), "no PNG left behind");
        // The per-page panels are slimmed to WebP too.
        assertTrue(Files.exists(report.resolve("before/p1.webp")), "before panel is webp");
        assertTrue(Files.exists(report.resolve("overlay/p1.webp")), "overlay panel is webp");
        assertFalse(Files.exists(report.resolve("before/p1.png")), "no PNG panel left behind");
    }

    @Test
    void flipbookIsWrittenWhenImg2webpIsAvailable(@TempDir Path tmp) throws Exception {
        assumeTrue(toolAvailable("img2webp"), "img2webp (libwebp) not installed");
        Path input = tmp.resolve("in");
        Path report = tmp.resolve("report");
        Files.createDirectories(input);

        // Uniform page size, so img2webp accepts every overlay frame.
        for (int i = 1; i <= 3; i++) {
            boolean[][] img = TestImages.blank(48, 64);
            TestImages.fillRect(img, 8, 8, 39, 55);
            TestImages.dot(img, 2, 2);
            TestImages.dot(img, 45, 60);
            TestImages.writePbm(input.resolve("page-%02d.pbm".formatted(i)), img);
        }

        int code =
                new DespeckleCli()
                        .run(
                                new String[] {
                                    input.toString(),
                                    tmp.resolve("out").toString(),
                                    "--glob",
                                    "*.pbm",
                                    "--jobs",
                                    "1",
                                    "--dpi",
                                    "300",
                                    "--speck-size",
                                    "3",
                                    "--no-remove-isolated-dust",
                                    "--report",
                                    report.toString(),
                                    "--flipbook",
                                    "--force"
                                });

        assertEquals(0, code, "the reported run succeeds");
        assertTrue(
                Files.exists(report.resolve("flipbook.webp")), "flip-book written with img2webp");
    }

    private static boolean artifactExists(Path dir, String base) {
        return Files.exists(dir.resolve(base + ".webp"))
                || Files.exists(dir.resolve(base + ".png"));
    }

    /** Whether {@code tool} can be launched on this host (so a WebP-path test is meaningful). */
    private static boolean toolAvailable(String tool) {
        try {
            Process process =
                    new ProcessBuilder(tool, "-version")
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
