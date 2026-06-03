package io.github.p4suta.despeckle.core;

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
}
