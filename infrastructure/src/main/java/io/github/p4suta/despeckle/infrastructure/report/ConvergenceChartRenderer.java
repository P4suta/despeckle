package io.github.p4suta.despeckle.infrastructure.report;

import io.github.p4suta.despeckle.domain.model.PageStat;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The corpus "did the noise collapse?" overlay. Every page's 8-connected component count is plotted
 * twice and joined: BEFORE on the left axis (the speck-inflated count) and AFTER on the right
 * (after the filter ran). A fan of lines sloping down and bunching together is the visible proof
 * that the dust went and the pages converged on their true glyph count, where the BEFORE column was
 * tall and scattered. Orange median ticks mark the center of each column. Pure rendering — no I/O.
 *
 * <p>This is the despeckle analogue of {@code register}'s corpus-overlay: there the before/after is
 * a column box in page vs canvas space; here it is a component count before vs after cleaning.
 */
final class ConvergenceChartRenderer {

    private static final int WIDTH = 720;
    private static final int HEIGHT = 480;
    private static final int PLOT_TOP = 70;
    private static final int PLOT_BOTTOM = HEIGHT - 60;
    private static final int LEFT_AXIS = (int) (WIDTH * 0.34);
    private static final int RIGHT_AXIS = (int) (WIDTH * 0.66);
    private static final int MIN_Y_SPAN = 4;

    private static final Color LINE = new Color(40, 120, 160);
    private static final Color DOT = new Color(20, 90, 130);
    private static final Color MEDIAN = new Color(255, 140, 0);

    private ConvergenceChartRenderer() {}

    /**
     * Render the before/after convergence chart for a whole run.
     *
     * @param pages every page's stat, in any order
     * @return the chart image
     */
    static BufferedImage render(List<PageStat> pages) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            Charts.antialias(g);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);

            g.setColor(Charts.TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            g.drawString("component convergence — before vs after cleaning", 24, 34);

            if (pages.isEmpty()) {
                g.setColor(Charts.TEXT);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                g.drawString("no pages to chart", 24, 70);
                return img;
            }

            int maxY = MIN_Y_SPAN;
            int[] beforeCounts = new int[pages.size()];
            int[] afterCounts = new int[pages.size()];
            for (int i = 0; i < pages.size(); i++) {
                beforeCounts[i] = pages.get(i).componentsBefore();
                afterCounts[i] = pages.get(i).componentsAfter();
                maxY = Math.max(maxY, Math.max(beforeCounts[i], afterCounts[i]));
            }

            drawAxes(g, maxY);

            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < pages.size(); i++) {
                int by = Charts.mapY(beforeCounts[i], maxY, PLOT_TOP, PLOT_BOTTOM);
                int ay = Charts.mapY(afterCounts[i], maxY, PLOT_TOP, PLOT_BOTTOM);
                g.setColor(Charts.alpha(LINE, 70));
                g.drawLine(LEFT_AXIS, by, RIGHT_AXIS, ay);
                g.setColor(Charts.alpha(DOT, 160));
                g.fillOval(LEFT_AXIS - 3, by - 3, 6, 6);
                g.fillOval(RIGHT_AXIS - 3, ay - 3, 6, 6);
            }

            drawMedian(g, LEFT_AXIS, Charts.median(beforeCounts), maxY, true);
            drawMedian(g, RIGHT_AXIS, Charts.median(afterCounts), maxY, false);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void drawAxes(Graphics2D g, int maxY) {
        g.setColor(Charts.AXIS);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(LEFT_AXIS, PLOT_TOP, LEFT_AXIS, PLOT_BOTTOM);
        g.drawLine(RIGHT_AXIS, PLOT_TOP, RIGHT_AXIS, PLOT_BOTTOM);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (int k = 0; k <= 2; k++) {
            int val = (int) Math.round(maxY * (k / 2.0));
            int yy = Charts.mapY(val, maxY, PLOT_TOP, PLOT_BOTTOM);
            g.setColor(Charts.GRIDLINE);
            g.drawLine(LEFT_AXIS, yy, RIGHT_AXIS, yy);
            g.setColor(Charts.TEXT);
            g.drawString(String.valueOf(val), LEFT_AXIS - 38, yy + 4);
        }

        g.setColor(Charts.TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString("BEFORE", LEFT_AXIS - 26, PLOT_BOTTOM + 22);
        g.drawString("AFTER", RIGHT_AXIS - 20, PLOT_BOTTOM + 22);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.drawString("connected-component count", 24, PLOT_TOP - 12);
    }

    private static void drawMedian(
            Graphics2D g, int axisX, int value, int maxY, boolean labelLeft) {
        int y = Charts.mapY(value, maxY, PLOT_TOP, PLOT_BOTTOM);
        g.setColor(MEDIAN);
        g.setStroke(new BasicStroke(2.5f));
        g.drawLine(axisX - 14, y, axisX + 14, y);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        String label = "median " + value;
        int w = g.getFontMetrics().stringWidth(label);
        g.drawString(label, labelLeft ? axisX - 18 - w : axisX + 18, y + 4);
    }
}
