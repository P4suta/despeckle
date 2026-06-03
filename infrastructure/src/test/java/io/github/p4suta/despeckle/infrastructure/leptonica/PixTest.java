package io.github.p4suta.despeckle.infrastructure.leptonica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.p4suta.despeckle.testsupport.TestImages;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FFM smoke test and the polarity / connectivity pins for {@link Pix}. */
final class PixTest {

    @Test
    void readsAndReportsDimensions(@TempDir Path dir) throws Exception {
        Path pbm = dir.resolve("box.pbm");
        boolean[][] img = TestImages.blank(16, 16);
        TestImages.fillRect(img, 4, 4, 11, 11);
        TestImages.writePbm(pbm, img);

        try (Pix pix = Pix.read(pbm)) {
            assertEquals(16, pix.width());
            assertEquals(16, pix.height());
            assertEquals(1, pix.connectedComponents());
            assertEquals(64L, pix.blackPixels());
        }
    }

    @Test
    void writeThenReadIsPixelIdentical(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src.pbm");
        Path dst = dir.resolve("dst.pbm");
        boolean[][] img = TestImages.blank(24, 24);
        TestImages.fillRect(img, 2, 2, 9, 20);
        TestImages.dot(img, 15, 15);
        TestImages.writePbm(src, img);

        try (Pix a = Pix.read(src)) {
            a.write(dst, Leptonica.IFF_PNM);
            try (Pix b = Pix.read(dst)) {
                assertTrue(a.pixelsEqual(b), "PBM round-trip must be pixel-identical");
            }
        }
    }

    @Test
    void keepLargerThanDropsDustButKeepsTallThinStroke(@TempDir Path dir) throws Exception {
        // A 1px-wide, 18px-tall vertical stroke (like a Japanese stroke) plus a
        // 2x2 dust speck. With k=3 the stroke must survive (tall) and the speck
        // must die (small on BOTH axes) — the exact case the old code botched.
        Path pbm = dir.resolve("mix.pbm");
        boolean[][] img = TestImages.blank(32, 32);
        TestImages.fillRect(img, 5, 4, 5, 21); // 1 x 18 stroke
        TestImages.fillRect(img, 20, 20, 21, 21); // 2 x 2 speck
        TestImages.writePbm(pbm, img);

        try (Pix pix = Pix.read(pbm)) {
            assertEquals(2, pix.connectedComponents());
            try (Pix kept = pix.keepComponentsLargerThan(3)) {
                assertEquals(1, kept.connectedComponents(), "tall stroke kept, speck removed");
                assertEquals(18L, kept.blackPixels(), "exactly the stroke's pixels remain");
            }
        }
    }

    @Test
    void resolutionIsZeroForPbmAndRoundTripsThroughPng(@TempDir Path dir) throws Exception {
        // PBM carries no resolution; PNG does. Stamping a resolution must survive a write/read.
        Path pbm = dir.resolve("res.pbm");
        Path png = dir.resolve("res.png");
        boolean[][] img = TestImages.blank(16, 16);
        TestImages.fillRect(img, 4, 4, 11, 11);
        TestImages.writePbm(pbm, img);

        try (Pix pix = Pix.read(pbm)) {
            assertEquals(0, pix.resolution(), "a PBM source carries no resolution");
            pix.setResolution(600);
            pix.writePng(png);
        }
        try (Pix reread = Pix.read(png)) {
            assertEquals(
                    600, reread.resolution(), "the stamped resolution survives the round-trip");
        }
    }

    @Test
    void invertIsReversible(@TempDir Path dir) throws Exception {
        Path pbm = dir.resolve("inv.pbm");
        boolean[][] img = TestImages.blank(20, 12);
        TestImages.fillRect(img, 3, 3, 8, 8);
        TestImages.writePbm(pbm, img);

        try (Pix pix = Pix.read(pbm);
                Pix once = pix.inverted();
                Pix twice = once.inverted()) {
            assertFalse(pix.pixelsEqual(once), "single inversion changes the image");
            assertTrue(pix.pixelsEqual(twice), "double inversion restores the image");
        }
    }
}
