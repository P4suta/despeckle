package io.github.p4suta.despeckle.infrastructure.report;

import io.github.p4suta.despeckle.domain.model.PageStat;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Plots each page's despeckle intensity against page index, one panel for the black-pixel removal
 * rate and one for the connected-component drop. A low, flat point cloud means a gentle, consistent
 * clean; a spike names the page that lost the most — and on the top panel, a page that crosses the
 * 3% over-removal line (the same guardrail {@code Runner} warns on) is drawn in red, so the eye
 * goes straight to a page that may have eaten text. Pure rendering — no I/O.
 *
 * <p>This is the despeckle analogue of {@code register}'s residual chart: there the y-value is the
 * distance to the type-area grid; here it is how much ink the filter took.
 */
final class RemovalChartRenderer {

    private static final int WIDTH = 900;
    private static final int PANEL_H = 260;
    private static final int MARGIN = 50;
    private static final int GAP = 50;
    private static final int X_LABEL_H = 30;
    private static final int TITLE_H = 22;

    /** The over-removal guardrail, in percent — matches {@code Runner.OVER_REMOVAL_WARN_RATIO}. */
    private static final double WARN_PERCENT = 3.0;

    private static final Color POINT = new Color(40, 120, 160);
    private static final Color WARN = new Color(210, 40, 40);

    private RemovalChartRenderer() {}

    /**
     * Render the two-panel removal chart for a whole run.
     *
     * @param pages every page's stat, in reading order
     * @return the chart image
     */
    static BufferedImage render(List<PageStat> pages) {
        int height = MARGIN + PANEL_H + GAP + PANEL_H + MARGIN;
        BufferedImage img = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            Charts.antialias(g);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, height);
            if (pages.isEmpty()) {
                g.setColor(Charts.TEXT);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                g.drawString("no pages to chart", MARGIN, MARGIN + 20);
                return img;
            }
            int maxIndex = pages.size() - 1;
            drawRemovalPanel(g, MARGIN, pages, maxIndex);
            drawPanel(
                    g,
                    MARGIN + PANEL_H + GAP,
                    "connected-component drop — specks removed per page",
                    pages,
                    maxIndex,
                    p -> p.componentsRemoved(),
                    1.0);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** The black-pixel panel: like {@link #drawPanel} but with the red over-removal line on top. */
    private static void drawRemovalPanel(
            Graphics2D g, int top, List<PageStat> pages, int maxIndex) {
        int left = MARGIN;
        int right = WIDTH - MARGIN;
        int plotTop = top + TITLE_H;
        int bottom = top + PANEL_H - X_LABEL_H;

        double maxY = WARN_PERCENT + 1;
        for (PageStat p : pages) {
            maxY = Math.max(maxY, p.removedRatio() * 100.0);
        }

        drawFrameAndGrid(
                g,
                top,
                "black-pixel removal (%) — 3% line flags possible over-removal",
                left,
                right,
                plotTop,
                bottom,
                maxY);

        // The guardrail line, dashed, with a label.
        int warnY = Charts.mapY(WARN_PERCENT, maxY, plotTop, bottom);
        g.setColor(WARN);
        g.setStroke(
                new BasicStroke(
                        1f,
                        BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER,
                        1f,
                        new float[] {5f, 4f},
                        0f));
        g.drawLine(left, warnY, right, warnY);
        g.setStroke(new BasicStroke(1f));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.drawString("3% over-removal", left + 6, warnY - 4);

        for (int i = 0; i < pages.size(); i++) {
            double pct = pages.get(i).removedRatio() * 100.0;
            int px = Charts.mapX(i, maxIndex, left, right);
            int py = Charts.mapY(pct, maxY, plotTop, bottom);
            // Match Runner's guardrail exactly: a ratio strictly over 3% is the over-removal flag.
            g.setColor(pct > WARN_PERCENT ? WARN : Charts.alpha(POINT, 200));
            g.fillOval(px - 3, py - 3, 6, 6);
        }
    }

    private static void drawPanel(
            Graphics2D g,
            int top,
            String title,
            List<PageStat> pages,
            int maxIndex,
            ToDoubleFunction<PageStat> value,
            double minSpan) {
        int left = MARGIN;
        int right = WIDTH - MARGIN;
        int plotTop = top + TITLE_H;
        int bottom = top + PANEL_H - X_LABEL_H;

        double maxY = minSpan;
        for (PageStat p : pages) {
            maxY = Math.max(maxY, value.applyAsDouble(p));
        }

        drawFrameAndGrid(g, top, title, left, right, plotTop, bottom, maxY);

        for (int i = 0; i < pages.size(); i++) {
            int px = Charts.mapX(i, maxIndex, left, right);
            int py = Charts.mapY(value.applyAsDouble(pages.get(i)), maxY, plotTop, bottom);
            g.setColor(Charts.alpha(POINT, 200));
            g.fillOval(px - 3, py - 3, 6, 6);
        }
    }

    private static void drawFrameAndGrid(
            Graphics2D g,
            int top,
            String title,
            int left,
            int right,
            int plotTop,
            int bottom,
            double maxY) {
        g.setColor(Charts.TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.drawString(title, left, top + 14);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (int k = 0; k <= 2; k++) {
            double val = maxY * (k / 2.0);
            int yy = Charts.mapY(val, maxY, plotTop, bottom);
            g.setColor(Charts.GRIDLINE);
            g.drawLine(left, yy, right, yy);
            g.setColor(Charts.TEXT);
            g.drawString(String.valueOf(Math.round(val)), left - 34, yy + 4);
        }

        g.setColor(Charts.AXIS);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(left, plotTop, left, bottom);
        g.drawLine(left, bottom, right, bottom);
        g.setColor(Charts.TEXT);
        g.drawString("page index →", right - 90, bottom + 22);
    }
}
