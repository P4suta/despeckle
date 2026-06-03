package io.github.p4suta.despeckle.infrastructure.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.p4suta.despeckle.domain.model.BatchBook;
import io.github.p4suta.despeckle.domain.model.BookStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The batch roll-up index: aggregate counts, per-book links, and failed/skipped rows. */
final class HtmlBatchReporterTest {

    private final HtmlBatchReporter reporter = new HtmlBatchReporter();

    @Test
    void writesAggregateAndLinksBooksWithReports(@TempDir Path tmp) throws Exception {
        List<BatchBook> books =
                List.of(
                        new BatchBook("a.pdf", "a", BookStatus.OK, 320, 1284, true),
                        new BatchBook("b.pdf", "b", BookStatus.OK, 298, 902, false),
                        new BatchBook("c.pdf", "c", BookStatus.FAILED, 0, 0, false),
                        new BatchBook("d.pdf", "d", BookStatus.SKIPPED, 0, 0, true));

        reporter.write(tmp, books);
        String html = Files.readString(tmp.resolve("index.html"));

        assertTrue(html.contains("4 book(s): 2 ok, 1 failed, 1 skipped"), "aggregate header");
        assertTrue(html.contains("618 page(s)"), "total pages = 320 + 298");
        assertTrue(html.contains("2186 component(s) removed"), "total removed = 1284 + 902");
        assertTrue(html.contains("a/index.html"), "ok book with a report is linked");
        assertFalse(html.contains("b/index.html"), "ok book without a report is not linked");
        assertTrue(html.contains("d/index.html"), "skipped book with an existing report is linked");
        assertTrue(html.contains("failed"), "the failed status is shown");
    }
}
