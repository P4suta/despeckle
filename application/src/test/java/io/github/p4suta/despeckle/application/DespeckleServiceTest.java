package io.github.p4suta.despeckle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.p4suta.despeckle.application.Fakes.FakePageCleaner;
import io.github.p4suta.despeckle.application.Fakes.RecordingReporterFactory;
import io.github.p4suta.despeckle.domain.model.OutputFormat;
import io.github.p4suta.despeckle.domain.model.ProcessOptions;
import io.github.p4suta.despeckle.domain.model.ProcessResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit-tests the corpus orchestrator against fake {@code PageCleaner}/{@code ReporterFactory}. */
final class DespeckleServiceTest {

    // 2% of black pixels removed — under the 3% over-removal threshold.
    private static final ProcessResult LIGHT = new ProcessResult(100, 90, 1000, 980);
    // 20% removed — over the threshold, so it raises an over-removal warning.
    private static final ProcessResult HEAVY = new ProcessResult(100, 50, 1000, 800);

    private static DespeckleService.Config config(
            Path in, Path out, boolean force, @org.jspecify.annotations.Nullable Path reportDir) {
        return new DespeckleService.Config(
                in,
                out,
                OutputFormat.SAME,
                "*.pbm",
                1,
                force,
                ProcessOptions.defaults(),
                reportDir,
                false);
    }

    private static void writeInputs(Path dir, int n) throws IOException {
        Files.createDirectories(dir);
        for (int i = 1; i <= n; i++) {
            Files.writeString(
                    dir.resolve("page-%02d.pbm".formatted(i)), "x", StandardCharsets.UTF_8);
        }
    }

    @Test
    void cleansEveryMatchingPage(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("in");
        Path out = tmp.resolve("out");
        writeInputs(in, 3);
        FakePageCleaner cleaner = new FakePageCleaner(LIGHT);

        DespeckleService.Summary summary =
                new DespeckleService(cleaner, new RecordingReporterFactory())
                        .run(config(in, out, false, null));

        assertEquals(3, summary.pages());
        assertEquals(30, summary.componentsRemoved(), "10 removed × 3 pages");
        assertEquals(0, summary.overRemovalWarnings());
        assertEquals(3, cleaner.calls.get());
        assertTrue(Files.exists(out.resolve("page-01.pbm")), "the cleaned page is mirrored out");
    }

    @Test
    void countsOverRemovalWarnings(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("in");
        writeInputs(in, 2);

        DespeckleService.Summary summary =
                new DespeckleService(new FakePageCleaner(HEAVY), new RecordingReporterFactory())
                        .run(config(in, tmp.resolve("out"), false, null));

        assertEquals(2, summary.overRemovalWarnings());
    }

    @Test
    void emptyInputIsAZeroSummary(@TempDir Path tmp) throws IOException {
        Path in = Files.createDirectories(tmp.resolve("in"));

        DespeckleService.Summary summary =
                new DespeckleService(new FakePageCleaner(LIGHT), new RecordingReporterFactory())
                        .run(config(in, tmp.resolve("out"), false, null));

        assertEquals(0, summary.pages());
    }

    @Test
    void refusesANonEmptyOutputWithoutForce(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("in");
        Path out = Files.createDirectories(tmp.resolve("out"));
        writeInputs(in, 1);
        Files.writeString(out.resolve("stale.txt"), "old", StandardCharsets.UTF_8);

        DespeckleService service =
                new DespeckleService(new FakePageCleaner(LIGHT), new RecordingReporterFactory());
        assertThrows(IOException.class, () -> service.run(config(in, out, false, null)));
    }

    @Test
    void drivesTheReporterWhenAReportDirIsGiven(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("in");
        Path report = tmp.resolve("report");
        writeInputs(in, 2);
        RecordingReporterFactory factory = new RecordingReporterFactory();

        new DespeckleService(new FakePageCleaner(LIGHT), factory)
                .run(config(in, tmp.resolve("out"), true, report));

        assertEquals(report, factory.createdFor, "the factory is asked to build the report sink");
        assertEquals(2, factory.reporter.pages.get(), "every page is reported");
        assertEquals(1, factory.reporter.finished.get(), "the report is finalized once");
    }
}
