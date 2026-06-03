package io.github.p4suta.despeckle.infrastructure.report;

import io.github.p4suta.despeckle.domain.model.BatchBook;
import io.github.p4suta.despeckle.domain.model.BookStatus;
import io.github.p4suta.despeckle.port.BatchReporter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The top-level batch report — the {@link BatchReporter} adapter. It writes one {@code index.html}
 * listing every book a {@code despeckle pipeline} batch touched, each linking to its own per-book
 * report, above a roll-up of the run (books cleaned / skipped / failed, total pages, total specks
 * removed). It is the batch analogue of {@link HtmlReporter}'s per-run {@code index.html}, and
 * shares its dark theme. Pure rendering plus the final write — no other I/O.
 */
public final class HtmlBatchReporter implements BatchReporter {

    /** Create a batch reporter. */
    public HtmlBatchReporter() {}

    /**
     * Write {@code reportParent/index.html} for {@code books}.
     *
     * @param reportParent the batch report root (created if absent)
     * @param books every book the batch processed, in reading order
     * @throws IOException if the index cannot be written
     */
    @Override
    public void write(Path reportParent, List<BatchBook> books) throws IOException {
        Files.createDirectories(reportParent);
        Files.writeString(
                reportParent.resolve("index.html"), renderHtml(books), StandardCharsets.UTF_8);
    }

    private static String renderHtml(List<BatchBook> books) {
        int ok = 0;
        int skipped = 0;
        int failed = 0;
        long totalPages = 0;
        long totalRemoved = 0;
        for (BatchBook book : books) {
            switch (book.status()) {
                case OK -> {
                    ok++;
                    totalPages += book.pages();
                    totalRemoved += book.componentsRemoved();
                }
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }

        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">")
                .append("<title>despeckle batch</title><style>")
                .append(
                        "body{font-family:system-ui,sans-serif;margin:2rem;background:#111;color:#eee}")
                .append("h1{font-size:1.2rem}table{border-collapse:collapse;width:100%}")
                .append("th,td{padding:.4rem .6rem;border-bottom:1px solid #333;text-align:left}")
                .append("th{font-weight:600;color:#aaa}")
                .append("td.num{text-align:right;font-variant-numeric:tabular-nums}")
                .append("a{color:#6cf;text-decoration:none}a:hover{text-decoration:underline}")
                .append(".book{font-family:ui-monospace,monospace}")
                .append(
                        ".ok{color:#6c6}.failed{color:#f66}.skipped{color:#aa6}</style></head><body>")
                .append("<h1>despeckle batch &mdash; ")
                .append(books.size())
                .append(" book(s): ")
                .append(ok)
                .append(" ok, ")
                .append(failed)
                .append(" failed, ")
                .append(skipped)
                .append(" skipped &bull; ")
                .append(totalPages)
                .append(" page(s) &bull; ")
                .append(totalRemoved)
                .append(" component(s) removed</h1><table>")
                .append("<tr><th>book</th><th>pages</th><th>removed</th><th>status</th></tr>");
        for (BatchBook book : books) {
            boolean okBook = book.status() == BookStatus.OK;
            String label = statusLabel(book.status());
            html.append("<tr><td class=\"book\">")
                    .append(bookCell(book))
                    .append("</td><td class=\"num\">")
                    .append(okBook ? Integer.toString(book.pages()) : "&mdash;")
                    .append("</td><td class=\"num\">")
                    .append(okBook ? Long.toString(book.componentsRemoved()) : "&mdash;")
                    .append("</td><td class=\"")
                    .append(statusClass(book.status()))
                    .append("\">")
                    .append(escape(label))
                    .append("</td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private static String bookCell(BatchBook book) {
        String label = escape(book.name());
        if (!book.hasReport()) {
            return label;
        }
        return "<a href=\"" + escape(book.stem()) + "/index.html\">" + label + "</a>";
    }

    /** The lowercase display label the old free-form status string carried. */
    private static String statusLabel(BookStatus status) {
        return switch (status) {
            case OK -> "ok";
            case SKIPPED -> "skipped";
            case FAILED -> "failed";
        };
    }

    private static String statusClass(BookStatus status) {
        return switch (status) {
            case OK -> "ok";
            case SKIPPED -> "skipped";
            case FAILED -> "failed";
        };
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
