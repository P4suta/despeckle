package io.github.p4suta.despeckle.report;

/**
 * One page's despeckle outcome, the unit every corpus chart plots. It is the report layer's own
 * value type — derived from {@code core.ProcessResult} at {@code addPage} time — so the renderers
 * never reach back into {@code core}.
 *
 * @param stem the page path relative to the input root, without extension
 * @param componentsBefore 8-connected component count of the input
 * @param componentsAfter 8-connected component count of the output
 * @param removedRatio fraction of black pixels removed, in {@code [0, 1]}
 */
record PageStat(String stem, int componentsBefore, int componentsAfter, double removedRatio) {

    /** Net drop in 8-connected components on this page. */
    int componentsRemoved() {
        return componentsBefore - componentsAfter;
    }

    /** Black pixels removed, as a percentage, rounded for display and the warning test. */
    int removedPercent() {
        return (int) Math.round(removedRatio * 100);
    }
}
