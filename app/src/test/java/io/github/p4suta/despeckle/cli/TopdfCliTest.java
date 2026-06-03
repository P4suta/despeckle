package io.github.p4suta.despeckle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the {@code despeckle topdf} sub-command's parse layer: routing through {@link DespeckleCli},
 * the exact-2-positionals contract, and the exit-code mapping (0 / 2 usage / 1 runtime). Tool-free
 * — every case short-circuits before {@code jbig2} runs.
 */
final class TopdfCliTest {

    private static int run(String... args) {
        return new DespeckleCli().run(args);
    }

    @Test
    void noPositionalsIsUsageError() {
        assertEquals(2, run("topdf"));
    }

    @Test
    void onePositionalIsUsageError() {
        assertEquals(2, run("topdf", "cleaned"));
    }

    @Test
    void threePositionalsIsUsageError() {
        assertEquals(2, run("topdf", "a", "b", "c"));
    }

    @Test
    void unknownOptionIsUsageError() {
        assertEquals(2, run("topdf", "a", "b", "--bogus"));
    }

    @Test
    void nonNumericDpiIsUsageError() {
        assertEquals(2, run("topdf", "a", "b", "--dpi", "x"));
    }

    @Test
    void helpExitsZero() {
        assertEquals(0, run("topdf", "--help"));
        assertEquals(0, run("topdf", "-h"));
    }

    @Test
    void nonPositiveDpiIsRuntimeError(@TempDir Path tmp) {
        assertEquals(
                1,
                run(
                        "topdf",
                        tmp.resolve("dir").toString(),
                        tmp.resolve("out.pdf").toString(),
                        "--dpi",
                        "0",
                        "--force"));
    }

    @Test
    void missingImageDirIsRuntimeError(@TempDir Path tmp) {
        // Jbig2PackService rejects a non-existent image directory before shelling out to any tool.
        assertEquals(
                1,
                run(
                        "topdf",
                        tmp.resolve("nodir").toString(),
                        tmp.resolve("out.pdf").toString(),
                        "--force"));
    }
}
