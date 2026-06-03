package io.github.p4suta.despeckle.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Derived figures and the over-removal guardrail of {@link ProcessResult}. */
final class ProcessResultTest {

    @Test
    void componentsRemovedIsTheNetBeforeAfterDrop() {
        assertEquals(25, new ProcessResult(120, 95, 0, 0).componentsRemoved());
    }

    @Test
    void componentsRemovedCanGoNegativeWhenHolesAreFilledBackIn() {
        assertEquals(-5, new ProcessResult(95, 100, 0, 0).componentsRemoved(), "filled > removed");
    }

    @Test
    void removedBlackPixelRatioIsTheRemovedFractionOfTheInput() {
        // 50 of 200 input black pixels removed -> 0.25.
        assertEquals(0.25, new ProcessResult(0, 0, 200, 150).removedBlackPixelRatio(), 1e-12);
    }

    @Test
    void removedBlackPixelRatioIsZeroForABlankInputPage() {
        // The blackPixelsBefore == 0 guard avoids a divide-by-zero.
        assertEquals(0.0, new ProcessResult(0, 0, 0, 0).removedBlackPixelRatio(), 0.0);
    }

    @Test
    void overRemovalThresholdIsThreePercent() {
        assertEquals(0.03, ProcessResult.OVER_REMOVAL_WARN_RATIO, 0.0);
    }

    @Test
    void isOverRemovalIsTrueStrictlyAboveTheThreshold() {
        // 4 of 100 removed -> 0.04 > 0.03.
        assertTrue(new ProcessResult(0, 0, 100, 96).isOverRemoval());
    }

    @Test
    void isOverRemovalIsFalseExactlyAtTheThreshold() {
        // 3 of 100 removed -> 0.03, which equals OVER_REMOVAL_WARN_RATIO bit-for-bit, so the
        // strictly-greater test must report no warning. This pins the boundary (> vs >=).
        assertEquals(0.03, new ProcessResult(0, 0, 100, 97).removedBlackPixelRatio(), 0.0);
        assertFalse(new ProcessResult(0, 0, 100, 97).isOverRemoval());
    }

    @Test
    void isOverRemovalIsFalseBelowTheThreshold() {
        // 2 of 100 removed -> 0.02 < 0.03.
        assertFalse(new ProcessResult(0, 0, 100, 98).isOverRemoval());
    }

    @Test
    void isOverRemovalIsFalseForABlankInputPage() {
        assertFalse(new ProcessResult(0, 0, 0, 0).isOverRemoval());
    }
}
