package io.github.p4suta.despeckle.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The report's per-page value type derives its display fields correctly. */
final class PageStatTest {

    @Test
    void componentsRemovedIsTheBeforeAfterDrop() {
        PageStat stat = new PageStat("p1", 120, 95, 0.012);
        assertEquals(25, stat.componentsRemoved());
    }

    @Test
    void removedPercentRoundsTheRatio() {
        assertEquals(1, new PageStat("p1", 1, 1, 0.014).removedPercent());
        assertEquals(2, new PageStat("p2", 1, 1, 0.015).removedPercent());
        assertEquals(0, new PageStat("p3", 1, 1, 0.0).removedPercent());
    }
}
