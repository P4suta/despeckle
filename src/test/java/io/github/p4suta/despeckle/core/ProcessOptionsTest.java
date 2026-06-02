package io.github.p4suta.despeckle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Resolution precedence and speck-size derivation for {@link ProcessOptions}. */
final class ProcessOptionsTest {

    private static ProcessOptions of(OptionalInt dpi, OptionalInt speck) {
        return new ProcessOptions(dpi, speck, true);
    }

    @Test
    void explicitSpeckSizeWinsOverEverything() {
        ProcessOptions options = of(OptionalInt.of(300), OptionalInt.of(5));
        assertEquals(5, options.speckSize(600), "explicit speck size ignores the resolution");
        assertEquals(5, options.speckSize(0));
    }

    @Test
    void explicitDpiWinsOverTheImageResolution() {
        ProcessOptions options = of(OptionalInt.of(300), OptionalInt.empty());
        assertEquals(3, options.speckSize(600), "the --dpi flag overrides the embedded 600");
        assertEquals(OptionalInt.of(300), options.resolution(600));
    }

    @Test
    void fallsBackToTheImageResolutionWhenNoDpiGiven() {
        ProcessOptions options = of(OptionalInt.empty(), OptionalInt.empty());
        assertEquals(6, options.speckSize(600), "~6 px at 600 dpi");
        assertEquals(3, options.speckSize(300), "~3 px at 300 dpi");
        assertEquals(OptionalInt.of(600), options.resolution(600));
    }

    @Test
    void assumesDefaultDpiButReportsNoResolutionWhenNothingIsKnown() {
        ProcessOptions options = of(OptionalInt.empty(), OptionalInt.empty());
        assertEquals(
                3, options.speckSize(0), "filter assumes DEFAULT_DPI (300) when nothing known");
        assertFalse(
                options.resolution(0).isPresent(),
                "but a guessed resolution is never asserted on output");
    }

    @Test
    void defaultsAutoDetect() {
        ProcessOptions defaults = ProcessOptions.defaults();
        assertFalse(defaults.dpi().isPresent());
        assertFalse(defaults.speckSizePx().isPresent());
        assertTrue(defaults.fillHoles());
    }

    @Test
    void rejectsNonPositiveValues() {
        assertThrows(
                IllegalArgumentException.class, () -> of(OptionalInt.of(0), OptionalInt.empty()));
        assertThrows(
                IllegalArgumentException.class, () -> of(OptionalInt.empty(), OptionalInt.of(-1)));
    }
}
