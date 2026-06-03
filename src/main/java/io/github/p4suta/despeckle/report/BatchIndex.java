package io.github.p4suta.despeckle.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The top-level batch report: one {@code index.html} listing every book a {@code despeckle
 * pipeline} batch touched, each linking to its own per-book report, above a roll-up of the run
 * (books cleaned / skipped / failed, total pages, total specks removed). It is the batch analogue
 * of {@link Report}'s per-run {@code index.html}, and shares its dark theme. Pure rendering plus
 * the final write — no other I/O.
 */
public final class BatchIndex {

    /**
     * One book's line in the batch index.
     *
     * @param name the source PDF's file name (display label)
     * @param stem the name without its {@code .pdf} extension (the per-book report sub-directory)
     * @param status {@code "ok"}, {@code "skipped"} or {@code "failed"}
     * @param pages pages cleaned (meaningful only when {@code ok})
     * @param componentsRemoved specks removed (meaningful only when {@code ok})
     * @param hasReport whether a per-book {@code <stem>/index.html} exists to link to
     */
    public record Book(
            String name,
            String stem,
            String status,
            int pages,
            long componentsRemoved,
            boolean hasReport) {}

    private BatchIndex() {}

    /**
     * Write {@code reportParent/index.html} for {@code books}.
     *
     * @param reportParent the batch report root (must exist)
     * @param books every book the batch processed, in reading order
     * @throws IOException if the index cannot be written
     */
    public static void write(Path reportParent, List<Book> books) throws IOException {
        Files.createDirectories(reportParent);
        Files.writeString(
                reportParent.resolve("index.html"), renderHtml(books), StandardCharsets.UTF_8);
    }

    private static String renderHtml(List<Book> books) {
        int ok = 0;
        int skipped = 0;
        int failed = 0;
        long totalPages = 0;
        long totalRemoved = 0;
        for (Book book : books) {
            switch (book.status()) {
                case "ok" -> {
                    ok++;
                    totalPages += book.pages();
                    totalRemoved += book.componentsRemoved();
                }
                case "skipped" -> skipped++;
                default -> failed++;
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
        for (Book book : books) {
            boolean okBook = "ok".equals(book.status());
            html.append("<tr><td class=\"book\">")
                    .append(bookCell(book))
                    .append("</td><td class=\"num\">")
                    .append(okBook ? Integer.toString(book.pages()) : "&mdash;")
                    .append("</td><td class=\"num\">")
                    .append(okBook ? Long.toString(book.componentsRemoved()) : "&mdash;")
                    .append("</td><td class=\"")
                    .append(statusClass(book.status()))
                    .append("\">")
                    .append(escape(book.status()))
                    .append("</td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private static String bookCell(Book book) {
        String label = escape(book.name());
        if (!book.hasReport()) {
            return label;
        }
        return "<a href=\"" + escape(book.stem()) + "/index.html\">" + label + "</a>";
    }

    private static String statusClass(String status) {
        return switch (status) {
            case "ok" -> "ok";
            case "skipped" -> "skipped";
            default -> "failed";
        };
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
