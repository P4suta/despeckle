package io.github.p4suta.despeckle.infrastructure.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** The parallel-histogram accumulation and the heat ramp. */
final class RemovedHeatmapTest {

    @Test
    void binIndexMapsCornersAndClampsTheFarEdge() {
        assertEquals(
                0, RemovedHeatmap.binIndex(0, 0, RemovedHeatmap.GRID_W, RemovedHeatmap.GRID_H));
        // The bottom-right pixel must land on the last cell, never one past it.
        int last = RemovedHeatmap.GRID_W * RemovedHeatmap.GRID_H - 1;
        assertEquals(
                last,
                RemovedHeatmap.binIndex(
                        RemovedHeatmap.GRID_W - 1,
                        RemovedHeatmap.GRID_H - 1,
                        RemovedHeatmap.GRID_W,
                        RemovedHeatmap.GRID_H));
    }

    @Test
    void emptyCorpusRendersWithoutAnyHeatPixel() {
        BufferedImage img = new RemovedHeatmap().render(0);
        assertFalse(hasHeatPixel(img), "an empty corpus paints no heat");
    }

    @Test
    void anAccumulatedSpeckPaintsAHotCell() {
        RemovedHeatmap heatmap = new RemovedHeatmap();
        long[] histogram = heatmap.threadHistogram();
        // Drive one bin to be the corpus maximum, so it renders as a saturated red cell.
        histogram[
                        RemovedHeatmap.binIndex(
                                RemovedHeatmap.GRID_W / 2,
                                RemovedHeatmap.GRID_H / 2,
                                RemovedHeatmap.GRID_W,
                                RemovedHeatmap.GRID_H)] =
                500;
        BufferedImage img = heatmap.render(1);
        assertTrue(hasHeatPixel(img), "the accumulated bin paints a hot cell");
    }

    /** Whether any pixel is a warm heat color (high red, low blue), i.e. not background or text. */
    private static boolean hasHeatPixel(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r >= 200 && b < 100 && g < 230) {
                    return true;
                }
            }
        }
        return false;
    }
}
