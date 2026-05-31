package io.github.p4suta.despeckle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.p4suta.despeckle.TestImages;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end pipeline behavior for {@link Despeckler}. */
final class DespecklerTest {

    private final Despeckler despeckler = new Despeckler();

    @Test
    void removesSpecksButPreservesGlyph(@TempDir Path dir) throws Exception {
        // A glyph-sized block plus three 1px specks scattered in the margin.
        Path src = dir.resolve("page.pbm");
        Path out = dir.resolve("page-out.pbm");
        boolean[][] img = TestImages.blank(40, 40);
        TestImages.fillRect(img, 8, 8, 19, 25); // 12 x 18 glyph
        TestImages.dot(img, 2, 2);
        TestImages.dot(img, 35, 30);
        TestImages.dot(img, 30, 4);
        TestImages.writePbm(src, img);

        ProcessResult result =
                despeckler.process(
                        src,
                        out,
                        OutputFormat.PBM,
                        new ProcessOptions(300, OptionalInt.of(3), false));

        // Three specks gone, glyph kept => 3 components removed.
        assertEquals(3, result.componentsRemoved());
        try (Pix cleaned = Pix.read(out)) {
            assertEquals(1, cleaned.connectedComponents());
            assertEquals(12L * 18L, cleaned.blackPixels(), "the whole glyph survives intact");
        }
    }

    @Test
    void speckFreePageRoundTripsPixelIdentical(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("clean.pbm");
        Path out = dir.resolve("clean-out.pbm");
        boolean[][] img = TestImages.blank(30, 30);
        TestImages.fillRect(img, 5, 5, 24, 24); // one big block, no dust
        TestImages.writePbm(src, img);

        ProcessResult result =
                despeckler.process(
                        src,
                        out,
                        OutputFormat.PBM,
                        new ProcessOptions(300, OptionalInt.of(3), false));

        assertEquals(0, result.componentsRemoved());
        assertEquals(0.0, result.removedBlackPixelRatio());
        try (Pix before = Pix.read(src);
                Pix after = Pix.read(out)) {
            assertTrue(before.pixelsEqual(after), "a dust-free page must come back unchanged");
        }
    }

    @Test
    void fillHolesClosesPinHoleInsideStroke(@TempDir Path dir) throws Exception {
        // A solid block with a single white pin-hole punched in the middle.
        Path src = dir.resolve("holed.pbm");
        Path out = dir.resolve("holed-out.pbm");
        boolean[][] img = TestImages.blank(30, 30);
        TestImages.fillRect(img, 6, 6, 23, 23);
        img[14][14] = false; // the pin-hole
        TestImages.writePbm(src, img);

        ProcessResult result =
                despeckler.process(
                        src,
                        out,
                        OutputFormat.PBM,
                        new ProcessOptions(300, OptionalInt.of(3), true));

        long solid = 18L * 18L;
        assertEquals(solid - 1, result.blackPixelsBefore());
        try (Pix cleaned = Pix.read(out)) {
            assertEquals(solid, cleaned.blackPixels(), "the pin-hole is filled back to solid");
        }
    }
}
