package io.github.p4suta.despeckle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.p4suta.despeckle.TestImages;
import io.github.p4suta.despeckle.core.OutputFormat;
import io.github.p4suta.despeckle.runner.Runner;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the hand-rolled Commons CLI parsing the old picocli annotations used to guarantee: exit-code
 * mapping (0 / 2 usage / 1 runtime), the on-by-default flag composition, the jobs clamp, and the
 * defaults. {@link Runner.Config} is built directly by {@code MainE2eTest}, so this is the only
 * coverage of the parse layer itself.
 */
final class DespeckleCliTest {

    // ---- exit 2: usage / parse / type errors (never reach the Runner) ----

    @Test
    void noArgumentsIsUsageError() {
        assertEquals(2, new DespeckleCli().run(new String[] {}));
    }

    @Test
    void onePositionalIsUsageError() {
        assertEquals(2, new DespeckleCli().run(new String[] {"in"}));
    }

    @Test
    void threePositionalsIsUsageError() {
        assertEquals(2, new DespeckleCli().run(new String[] {"in", "out", "extra"}));
    }

    @Test
    void unknownOptionIsUsageError() {
        assertEquals(2, new DespeckleCli().run(new String[] {"in", "out", "--bogus"}));
    }

    @Test
    void invalidFormatIsUsageError() {
        assertEquals(2, new DespeckleCli().run(new String[] {"in", "out", "--format", "jpeg"}));
    }

    @Test
    void nonNumericJobsIsUsageError() {
        assertEquals(2, new DespeckleCli().run(new String[] {"in", "out", "--jobs", "many"}));
    }

    @Test
    void nonNumericDpiIsUsageError() {
        assertEquals(2, new DespeckleCli().run(new String[] {"in", "out", "--dpi", "x"}));
    }

    // ---- exit 1: value validation rejected by ProcessOptions ----

    @Test
    void nonPositiveDpiIsRuntimeError(@TempDir Path tmp) throws Exception {
        Path in = Files.createDirectories(tmp.resolve("in"));
        Path out = tmp.resolve("out");
        assertEquals(
                1,
                new DespeckleCli()
                        .run(
                                new String[] {
                                    in.toString(), out.toString(), "--dpi", "0", "--force"
                                }));
    }

    // ---- exit 0: help / version short-circuit before the Runner ----

    @Test
    void helpExitsZero() {
        assertEquals(0, new DespeckleCli().run(new String[] {"--help"}));
        assertEquals(0, new DespeckleCli().run(new String[] {"-h"}));
    }

    @Test
    void versionExitsZero() {
        assertEquals(0, new DespeckleCli().run(new String[] {"--version"}));
        assertEquals(0, new DespeckleCli().run(new String[] {"-V"}));
    }

    // ---- defaults & flag composition (parse layer, no pipeline) ----

    @Test
    void defaultsMatchThePreviousPicocliWiring() throws Exception {
        Runner.Config config = DespeckleCli.toConfig(parse("in", "out"));
        assertEquals(OutputFormat.SAME, config.format());
        assertEquals("*.{pbm,png,tiff,tif}", config.glob());
        assertFalse(config.force());
        assertTrue(config.options().fillHoles(), "hole-filling on by default");
        assertTrue(config.options().removeIsolatedDust(), "isolated-dust pass on by default");
    }

    @Test
    void noFillHolesOptsOut() throws Exception {
        DespeckleCli.Parsed parsed = parse("in", "out", "--no-fill-holes");
        assertFalse(parsed.fillHoles());
        assertTrue(parsed.removeIsolatedDust(), "only hole-filling is disabled");
    }

    @Test
    void noRemoveIsolatedDustOptsOut() throws Exception {
        DespeckleCli.Parsed parsed = parse("in", "out", "--no-remove-isolated-dust");
        assertFalse(parsed.removeIsolatedDust());
        assertTrue(parsed.fillHoles(), "only the isolated-dust pass is disabled");
    }

    @Test
    void jobsAreClampedToAtLeastOne() throws Exception {
        assertEquals(1, DespeckleCli.toConfig(parse("in", "out", "--jobs", "0")).jobs());
    }

    // ---- a real run through the full parse -> config -> pipeline path ----

    @Test
    void realRunWithFlagsSucceeds(@TempDir Path tmp) throws Exception {
        Path input = Files.createDirectories(tmp.resolve("input"));
        for (int i = 1; i <= 2; i++) {
            boolean[][] img = TestImages.blank(24, 24);
            TestImages.fillRect(img, 4, 4, 15, 19);
            TestImages.dot(img, 1, 1);
            TestImages.writePbm(input.resolve("page-%02d.pbm".formatted(i)), img);
        }
        int code =
                new DespeckleCli()
                        .run(
                                new String[] {
                                    input.toString(),
                                    tmp.resolve("output").toString(),
                                    "--jobs",
                                    "0",
                                    "--no-fill-holes",
                                    "--no-remove-isolated-dust",
                                    "--force"
                                });
        assertEquals(0, code);
    }

    private static DespeckleCli.Parsed parse(String... args) throws ParseException {
        return new DespeckleCli()
                .parseArgs(new DefaultParser().parse(DespeckleOptions.build(), args));
    }
}
