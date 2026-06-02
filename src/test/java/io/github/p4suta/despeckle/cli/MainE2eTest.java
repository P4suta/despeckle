package io.github.p4suta.despeckle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            TestImages.writePbm(input.resolve(String.format("page-%02d.pbm", i)), img);
        }

        Runner.Config config =
                new Runner.Config(
                        input,
                        output,
                        OutputFormat.SAME,
                        "*.{pbm,png,tiff,tif}",
                        2,
                        true,
                        new ProcessOptions(OptionalInt.of(300), OptionalInt.of(3), true),
                        null);
        Runner.Summary summary = new Runner().run(config);

        assertEquals(3, summary.pages());
        try (Stream<Path> entries = Files.list(output)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).sorted().toList();
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
                        report);
        new Runner().run(config);

        assertTrue(Files.exists(report.resolve("index.html")), "index.html written");
        assertTrue(Files.exists(report.resolve("before/p1.png")), "before panel written");
        assertTrue(Files.exists(report.resolve("overlay/p1.png")), "overlay panel written");
        assertTrue(Files.exists(report.resolve("after/p1.png")), "after panel written");
    }
}
