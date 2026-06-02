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
                        ProcessOptions.of(OptionalInt.of(300), OptionalInt.of(3), false));

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
                        ProcessOptions.of(OptionalInt.of(300), OptionalInt.of(3), false));

        assertEquals(0, result.componentsRemoved());
        assertEquals(0.0, result.removedBlackPixelRatio());
        try (Pix before = Pix.read(src);
                Pix after = Pix.read(out)) {
            assertTrue(before.pixelsEqual(after), "a dust-free page must come back unchanged");
        }
    }

    @Test
    void stampsResolvedResolutionOntoOutput(@TempDir Path dir) throws Exception {
        // A PBM input carries no resolution; with an explicit --dpi the cleaned PNG must come back
        // carrying that resolution as an accurate tag.
        Path src = dir.resolve("page.pbm");
        Path out = dir.resolve("page-out.png");
        boolean[][] img = TestImages.blank(20, 20);
        TestImages.fillRect(img, 4, 4, 15, 15);
        TestImages.writePbm(src, img);

        despeckler.process(
                src,
                out,
                OutputFormat.PNG,
                ProcessOptions.of(OptionalInt.of(600), OptionalInt.empty(), false));

        try (Pix cleaned = Pix.read(out)) {
            assertEquals(600, cleaned.resolution(), "the honored resolution is stamped on output");
        }
    }

    @Test
    void leavesResolutionUnsetWhenNoneIsKnown(@TempDir Path dir) throws Exception {
        // No --dpi and a resolution-less PBM input: the tool must not fabricate a tag on output.
        Path src = dir.resolve("page.pbm");
        Path out = dir.resolve("page-out.png");
        boolean[][] img = TestImages.blank(20, 20);
        TestImages.fillRect(img, 4, 4, 15, 15);
        TestImages.writePbm(src, img);

        despeckler.process(src, out, OutputFormat.PNG, ProcessOptions.defaults());

        try (Pix cleaned = Pix.read(out)) {
            assertEquals(0, cleaned.resolution(), "an unknown resolution is left unstamped");
        }
    }

    @Test
    void removesIsolatedSpeckButKeepsTheNeighborOfAGlyph(@TempDir Path dir) throws Exception {
        // A glyph, an 8x8 speck hugging it (a stand-in for a dakuten), and an 8x8 speck off on
        // clean background. Both specks clear the base speck size, so only the isolated pass can
        // tell them apart: the neighbor is kept, the isolated one is dropped.
        Path src = dir.resolve("page.pbm");
        boolean[][] img = TestImages.blank(100, 60);
        TestImages.fillRect(img, 0, 10, 29, 49); // 30x40 glyph (real text)
        TestImages.fillRect(img, 32, 12, 39, 19); // 8x8 speck, 3 px from the glyph -> kept
        TestImages.fillRect(img, 88, 2, 95, 9); // 8x8 speck, far on clean background -> removed
        TestImages.writePbm(src, img);

        // Without the pass, the base filter (speck size 3) keeps every 8x8 block.
        Path plain = dir.resolve("plain.pbm");
        ProcessResult before =
                despeckler.process(
                        src,
                        plain,
                        OutputFormat.PBM,
                        ProcessOptions.of(OptionalInt.empty(), OptionalInt.of(3), false));
        assertEquals(0, before.componentsRemoved(), "base filter keeps both 8x8 specks");

        // With the pass on, the isolated speck goes and the glyph-hugging one stays.
        Path out = dir.resolve("page-out.pbm");
        ProcessResult result =
                despeckler.process(
                        src,
                        out,
                        OutputFormat.PBM,
                        new ProcessOptions(
                                OptionalInt.empty(),
                                OptionalInt.of(3),
                                false,
                                true,
                                OptionalInt.of(10)));

        assertEquals(1, result.componentsRemoved(), "only the isolated speck is removed");
        try (Pix cleaned = Pix.read(out)) {
            assertEquals(2, cleaned.connectedComponents(), "glyph and its neighbor survive");
            assertEquals(
                    30L * 40L + 8L * 8L,
                    cleaned.blackPixels(),
                    "the glyph and the hugging speck are intact to the pixel");
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
                        ProcessOptions.of(OptionalInt.of(300), OptionalInt.of(3), true));

        long solid = 18L * 18L;
        assertEquals(solid - 1, result.blackPixelsBefore());
        try (Pix cleaned = Pix.read(out)) {
            assertEquals(solid, cleaned.blackPixels(), "the pin-hole is filled back to solid");
        }
    }

    @Test
    void fillHolesSparesTheGapInsideAThinWalledGlyph(@TempDir Path dir) throws Exception {
        // A 9x9 box with 2 px walls around a 5x5 white interior — a stand-in for a small glyph
        // whose inner gap is no larger than the speck size. A plain "fill every small hole" pass
        // would close it (crushing the glyph); the thickness-aware pass leaves it, because the
        // surrounding walls are thinner than the gap.
        Path src = dir.resolve("box.pbm");
        Path out = dir.resolve("box-out.pbm");
        boolean[][] img = TestImages.blank(24, 24);
        TestImages.fillRect(img, 8, 8, 16, 16); // 9x9 block
        for (int y = 10; y <= 14; y++) {
            for (int x = 10; x <= 14; x++) {
                img[y][x] = false; // 5x5 interior gap, leaving 2 px walls
            }
        }
        TestImages.writePbm(src, img);

        despeckler.process(
                src,
                out,
                OutputFormat.PBM,
                ProcessOptions.of(OptionalInt.of(600), OptionalInt.of(6), true));

        long ring = 9L * 9L - 5L * 5L;
        try (Pix cleaned = Pix.read(out)) {
            assertEquals(
                    ring, cleaned.blackPixels(), "the thin-walled gap is preserved, not filled");
        }
    }
}
