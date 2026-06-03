package io.github.p4suta.despeckle.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Covers every arm of {@link OutputFormat#extension()}. */
final class OutputFormatTest {

    @Test
    void sameKeepsTheInputExtension() {
        assertNull(OutputFormat.SAME.extension(), "SAME has no fixed extension");
    }

    @Test
    void concreteFormatsMapToTheirExtension() {
        assertEquals("pbm", OutputFormat.PBM.extension());
        assertEquals("png", OutputFormat.PNG.extension());
        assertEquals("tif", OutputFormat.TIFF.extension());
    }

    @Test
    void everyConstantIsExercised() {
        // Guards against a new constant slipping in without an extension() arm.
        for (OutputFormat format : OutputFormat.values()) {
            assertEquals(format, OutputFormat.valueOf(format.name()));
        }
    }
}
