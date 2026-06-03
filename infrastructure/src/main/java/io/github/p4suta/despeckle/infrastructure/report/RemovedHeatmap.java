package io.github.p4suta.despeckle.infrastructure.report;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Accumulates, across the whole corpus, <em>where on the page</em> the filter removed ink, then
 * renders it as a heatmap. Each page's removed pixels (dark before, light after) are dropped into a
 * fixed normalized grid — so pages of slightly different sizes still stack — and the result is
 * colored white (nothing) through yellow to red (many pages lost a speck at that spot). A bright
 * band hugging one margin is a dirty platen region; a smear over the text block is the warning sign
 * that the filter is biting glyphs, not dust.
 *
 * <p>This is despeckle's own diagnostic — {@code register} has no equivalent — and the spatial twin
 * of the metric charts. It assumes the corpus is one roughly-uniform page shape (a scanned book),
 * which is exactly when the normalized stack is meaningful.
 *
 * <h2>Concurrency: the parallel-histogram pattern</h2>
 *
 * The worker pool calls {@link #threadHistogram()} once per page and increments cells with no lock.
 * Each thread owns one partial histogram (a {@link ThreadLocal}, registered in {@link #partials} on
 * first use), so accumulation is contention-free; the only synchronization is the single {@code
 * add} when a thread first appears. {@link #render(int)} runs after the pool has drained and folds
 * the {@code O(threads)} partials together — never {@code O(pages)} merges, and zero per-page
 * allocation in the hot loop.
 */
final class RemovedHeatmap {

    /** Normalized grid resolution; portrait, to match a book page. */
    static final int GRID_W = 256;

    static final int GRID_H = 384;

    private static final int CELLS = GRID_W * GRID_H;

    /** On-screen pixels per grid cell. */
    private static final int SCALE = 2;

    private static final int MARGIN = 20;
    private static final int TITLE_H = 40;
    private static final int LEGEND_H = 26;

    /** Every worker thread's partial histogram, combined once in {@link #render(int)}. */
    private final ConcurrentLinkedQueue<long[]> partials = new ConcurrentLinkedQueue<>();

    private final ThreadLocal<long[]> threadLocal =
            ThreadLocal.withInitial(
                    () -> {
                        long[] histogram = new long[CELLS];
                        partials.add(histogram);
                        return histogram;
                    });

    /**
     * The calling thread's partial histogram. Fetch once per page, then increment {@code
     * [binIndex]} with no synchronization — each thread writes only its own array.
     */
    long[] threadHistogram() {
        return threadLocal.get();
    }

    /** The grid bin for an image coordinate, given the page's pixel size. Clamped to the grid. */
    static int binIndex(int x, int y, int width, int height) {
        int gx = (int) ((long) x * GRID_W / width);
        int gy = (int) ((long) y * GRID_H / height);
        if (gx >= GRID_W) {
            gx = GRID_W - 1;
        }
        if (gy >= GRID_H) {
            gy = GRID_H - 1;
        }
        return gy * GRID_W + gx;
    }

    /**
     * Render the combined heatmap.
     *
     * @param pageCount number of pages summed in, for the caption
     * @return the heatmap image
     */
    BufferedImage render(int pageCount) {
        long[] grid = new long[CELLS];
        for (long[] partial : partials) {
            for (int i = 0; i < CELLS; i++) {
                grid[i] += partial[i];
            }
        }
        long max = 0;
        long total = 0;
        for (long v : grid) {
            if (v > max) {
                max = v;
            }
            total += v;
        }

        int width = MARGIN + GRID_W * SCALE + MARGIN;
        int height = TITLE_H + GRID_H * SCALE + LEGEND_H;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            Charts.antialias(g);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            g.setColor(Charts.TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.drawString(
                    "removed-pixel heatmap — where dust was cleaned across "
                            + pageCount
                            + " page(s)",
                    MARGIN,
                    26);

            int x0 = MARGIN;
            int y0 = TITLE_H;
            if (max == 0) {
                g.setColor(new Color(245, 245, 245));
                g.fillRect(x0, y0, GRID_W * SCALE, GRID_H * SCALE);
                g.setColor(Charts.TEXT);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                g.drawString("no pixels were removed across the corpus", x0 + 16, y0 + 28);
            } else {
                paintCells(g, grid, max, x0, y0);
            }
            g.setColor(new Color(200, 200, 200));
            g.drawRect(x0, y0, GRID_W * SCALE, GRID_H * SCALE);

            g.setColor(Charts.TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString(
                    "white = none   yellow → red = more pages removed a speck here   ("
                            + total
                            + " px total)",
                    MARGIN,
                    height - 9);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void paintCells(Graphics2D g, long[] grid, long max, int x0, int y0) {
        for (int gy = 0; gy < GRID_H; gy++) {
            int rowBase = gy * GRID_W;
            for (int gx = 0; gx < GRID_W; gx++) {
                long count = grid[rowBase + gx];
                if (count == 0) {
                    continue;
                }
                g.setColor(heat((double) count / max));
                g.fillRect(x0 + gx * SCALE, y0 + gy * SCALE, SCALE, SCALE);
            }
        }
    }

    /**
     * White → yellow → red ramp. A perceptual lift ({@code sqrt} plus a floor) keeps a single-page
     * speck faintly visible against the white field instead of washing out next to a hot margin.
     */
    private static Color heat(double frac) {
        double t = frac <= 0 ? 0 : Math.min(1.0, 0.2 + 0.8 * Math.sqrt(frac));
        int r;
        int green;
        int b;
        if (t < 0.5) {
            double f = t / 0.5;
            r = 255;
            green = (int) Math.round(255 - 45 * f);
            b = (int) Math.round(255 - 255 * f);
        } else {
            double f = (t - 0.5) / 0.5;
            r = (int) Math.round(255 - 55 * f);
            green = (int) Math.round(210 - 210 * f);
            b = 0;
        }
        return new Color(clamp(r), clamp(green), clamp(b));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
