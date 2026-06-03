package io.github.p4suta.despeckle.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the {@link BatchBook} value record and the {@link BookStatus} enum. */
final class BatchBookTest {

    @Test
    void carriesItsFields() {
        BatchBook book = new BatchBook("book.pdf", "book", BookStatus.OK, 320, 1284, true);
        assertEquals("book.pdf", book.name());
        assertEquals("book", book.stem());
        assertEquals(BookStatus.OK, book.status());
        assertEquals(320, book.pages());
        assertEquals(1284, book.componentsRemoved());
        assertTrue(book.hasReport());
    }

    @Test
    void everyStatusRoundTrips() {
        for (BookStatus status : BookStatus.values()) {
            assertEquals(status, BookStatus.valueOf(status.name()));
        }
        assertEquals(3, BookStatus.values().length, "OK / SKIPPED / FAILED");
    }
}
