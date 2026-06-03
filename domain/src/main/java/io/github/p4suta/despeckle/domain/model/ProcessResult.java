package io.github.p4suta.despeckle.domain.model;

/**
 * The outcome of despeckling one page.
 *
 * @param componentsBefore 8-connected component count of the input
 * @param componentsAfter 8-connected component count of the output
 * @param blackPixelsBefore foreground pixel count of the input
 * @param blackPixelsAfter foreground pixel count of the output
 */
public record ProcessResult(
        int componentsBefore, int componentsAfter, long blackPixelsBefore, long blackPixelsAfter) {

    /**
     * A black-pixel removal ratio above this flags a possibly over-cleaned page. This is the single
     * domain home for the over-removal threshold the runner warns on and the report highlights.
     */
    public static final double OVER_REMOVAL_WARN_RATIO = 0.03;

    /**
     * Net drop in 8-connected components — dust removed minus any holes filled back in. The
     * headline "how many specks went" figure, summed into the run total and plotted per page in the
     * report.
     *
     * @return {@code componentsBefore - componentsAfter}
     */
    public int componentsRemoved() {
        return componentsBefore - componentsAfter;
    }

    /**
     * Fraction of the input's black pixels that were removed. A surprisingly high value flags a
     * page where the filter may have eaten real text — the quantitative guardrail against the
     * over-removal that sank the old implementation.
     *
     * @return the removed fraction in {@code [0, 1]}
     */
    public double removedBlackPixelRatio() {
        if (blackPixelsBefore == 0) {
            return 0.0;
        }
        return (double) (blackPixelsBefore - blackPixelsAfter) / blackPixelsBefore;
    }

    /**
     * Whether this page removed enough black pixels to warrant an over-removal warning — i.e. its
     * {@link #removedBlackPixelRatio()} exceeds {@link #OVER_REMOVAL_WARN_RATIO}. Centralizes the
     * threshold the runner logs on and the report flags.
     *
     * @return {@code true} when the removed-pixel ratio is over the warn threshold
     */
    public boolean isOverRemoval() {
        return removedBlackPixelRatio() > OVER_REMOVAL_WARN_RATIO;
    }
}
