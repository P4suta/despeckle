package io.github.p4suta.despeckle.report;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Arrays;

/**
 * Small shared scaffolding for the corpus charts — antialiasing, value→pixel mapping, alpha tints
 * and a median — so {@link RemovalChartRenderer} and {@link ConvergenceChartRenderer} draw on a
 * common grid instead of each re-deriving it. Pure helpers, no state.
 */
final class Charts {

    static final Color AXIS = new Color(120, 120, 120);
    static final Color GRIDLINE = new Color(225, 225, 225);
    static final Color TEXT = new Color(20, 20, 20);

    private Charts() {}

    /** Turn on shape and text antialiasing for a crisp chart. */
    static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /** Map a page index in {@code [0, maxIndex]} onto a pixel x in {@code [left, right]}. */
    static int mapX(int index, int maxIndex, int left, int right) {
        if (maxIndex <= 0) {
            return left + (right - left) / 2;
        }
        return left + (int) Math.round((double) index / maxIndex * (right - left));
    }

    /**
     * Map a value in {@code [0, maxY]} onto a pixel y in {@code [plotTop, bottom]} (y grows down).
     */
    static int mapY(double value, double maxY, int plotTop, int bottom) {
        if (maxY <= 0) {
            return bottom;
        }
        return bottom - (int) Math.round(value / maxY * (bottom - plotTop));
    }

    /** The same color at a new alpha. */
    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** The (upper) median of {@code values}, or {@code 0} when empty. Does not mutate the input. */
    static int median(int[] values) {
        if (values.length == 0) {
            return 0;
        }
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
