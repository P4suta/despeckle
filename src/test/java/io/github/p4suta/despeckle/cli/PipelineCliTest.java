package io.github.p4suta.despeckle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@code despeckle pipeline} sub-command's parse layer: routing through {@link
 * DespeckleCli}, the exact-2-positionals contract, the flag composition, and the exit-code mapping
 * (0 / 2 usage / 1 runtime). Tool-free — every case short-circuits before any external tool runs.
 */
final class PipelineCliTest {

    private static int run(String... args) {
        return new DespeckleCli().run(args);
    }

    // ---- exit 2: usage / parse / type errors ----

    @Test
    void noPositionalsIsUsageError() {
        assertEquals(2, run("pipeline"));
    }

    @Test
    void onePositionalIsUsageError() {
        assertEquals(2, run("pipeline", "in.pdf"));
    }

    @Test
    void threePositionalsIsUsageError() {
        assertEquals(2, run("pipeline", "a.pdf", "b.pdf", "c.pdf"));
    }

    @Test
    void unknownOptionIsUsageError() {
        assertEquals(2, run("pipeline", "a.pdf", "b.pdf", "--bogus"));
    }

    @Test
    void nonNumericJobsIsUsageError() {
        assertEquals(2, run("pipeline", "a.pdf", "b.pdf", "--jobs", "many"));
    }

    @Test
    void nonNumericDpiIsUsageError() {
        assertEquals(2, run("pipeline", "a.pdf", "b.pdf", "--dpi", "x"));
    }

    @Test
    void flipbookWithoutReportIsUsageError() {
        assertEquals(2, run("pipeline", "a.pdf", "b.pdf", "--flipbook"));
    }

    // ---- exit 0: help short-circuits before any work ----

    @Test
    void helpExitsZero() {
        assertEquals(0, run("pipeline", "--help"));
        assertEquals(0, run("pipeline", "-h"));
    }

    // ---- exit 1: value validation / runtime, still tool-free ----

    @Test
    void nonPositiveDpiIsRuntimeError(@TempDir Path tmp) {
        assertEquals(
                1,
                run(
                        "pipeline",
                        tmp.resolve("in.pdf").toString(),
                        tmp.resolve("out.pdf").toString(),
                        "--dpi",
                        "0",
                        "--force"));
    }

    @Test
    void missingInputPdfIsRuntimeError(@TempDir Path tmp) {
        // PdfPipeline rejects a non-existent input before shelling out to any tool.
        assertEquals(
                1,
                run(
                        "pipeline",
                        tmp.resolve("nope.pdf").toString(),
                        tmp.resolve("out.pdf").toString(),
                        "--force"));
    }
}
