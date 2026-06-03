package io.github.p4suta.despeckle.infrastructure.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.p4suta.despeckle.domain.model.PageStat;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The two metric charts render at a fixed size for both an empty and a populated corpus. */
final class ChartRenderersTest {

    private static final List<PageStat> CORPUS =
            List.of(
                    new PageStat("p1", 100, 96, 0.004),
                    new PageStat("p2", 130, 118, 0.018),
                    new PageStat("p3", 90, 70, 0.052), // crosses the 3% over-removal line
                    new PageStat("p4", 110, 104, 0.009));

    @Test
    void removalChartRendersEmptyAndPopulated() {
        BufferedImage empty = RemovalChartRenderer.render(List.of());
        assertEquals(900, empty.getWidth());
        assertTrue(empty.getHeight() > 0);

        BufferedImage chart = RemovalChartRenderer.render(CORPUS);
        assertEquals(900, chart.getWidth());
        assertEquals(empty.getHeight(), chart.getHeight(), "the panel layout is fixed");
        assertTrue(hasWarnRed(chart), "the over-removal page is drawn red");
    }

    @Test
    void convergenceChartRendersEmptyAndPopulated() {
        BufferedImage empty = ConvergenceChartRenderer.render(List.of());
        assertEquals(720, empty.getWidth());
        assertEquals(480, empty.getHeight());

        BufferedImage chart = ConvergenceChartRenderer.render(CORPUS);
        assertEquals(720, chart.getWidth());
        assertEquals(480, chart.getHeight());
    }

    /** Whether any pixel is the chart's warn red (the over-removal marker color). */
    private static boolean hasWarnRed(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r >= 180 && g < 90 && b < 90) {
                    return true;
                }
            }
        }
        return false;
    }
}
