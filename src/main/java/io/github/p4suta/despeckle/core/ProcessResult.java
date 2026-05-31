package io.github.p4suta.despeckle.core;

/**
 * The outcome of despeckling one page.
 *
 * @param componentsRemoved net drop in 8-connected components (dust removed minus any holes filled
 *     back in)
 * @param blackPixelsBefore foreground pixel count of the input
 * @param blackPixelsAfter foreground pixel count of the output
 */
public record ProcessResult(int componentsRemoved, long blackPixelsBefore, long blackPixelsAfter) {

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
