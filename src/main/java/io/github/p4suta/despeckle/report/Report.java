package io.github.p4suta.despeckle.report;

import io.github.p4suta.despeckle.core.Pix;
import io.github.p4suta.despeckle.core.ProcessResult;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;

/**
 * Optional before / overlay / after report.
 *
 * <p>For each page it writes the original and cleaned images as PNG (via Leptonica) plus an overlay
 * that paints every removed pixel red over the original, then emits an {@code index.html}. This is
 * exactly the human eyeballing surface that lets you confirm dust was removed without eating
 * punctuation or ruby. It is read-only with respect to the pipeline.
 */
public final class Report {

    private static final int RED = 0xFF0000;
    private static final int LUMA_MIDPOINT = 128;
    private static final int OVER_REMOVAL_WARN_PERCENT = 3;

    private final Path outDir;
    private final ConcurrentLinkedQueue<Row> rows = new ConcurrentLinkedQueue<>();

    private Report(Path outDir) {
        this.outDir = outDir;
    }

    /**
     * Create the report directory tree.
     *
     * @param outDir the report root
     * @return a ready report
     * @throws IOException if the directories cannot be created
     */
    public static Report create(Path outDir) throws IOException {
        for (String panel : List.of("before", "overlay", "after")) {
            Files.createDirectories(outDir.resolve(panel));
        }
        return new Report(outDir);
    }

    /**
     * Render and record the three panels for one page. Thread-safe.
     *
     * @param relativeStem page path relative to the input root
     * @param inputPath original page on disk
     * @param outputPath cleaned page on disk
     * @param result the per-page outcome
     * @throws IOException if a panel cannot be written
     */
    public void addPage(Path relativeStem, Path inputPath, Path outputPath, ProcessResult result)
            throws IOException {
        String stem = stripExtension(relativeStem.toString());
        Path beforePng = panelPath("before", stem);
        Path afterPng = panelPath("after", stem);
        Path overlayPng = panelPath("overlay", stem);

        try (Pix before = Pix.read(inputPath)) {
            before.writePng(beforePng);
        }
        try (Pix after = Pix.read(outputPath)) {
            after.writePng(afterPng);
        }
        writeOverlay(beforePng, afterPng, overlayPng);

        rows.add(new Row(stem, result.componentsRemoved(), result.removedBlackPixelRatio()));
    }

    /**
     * Write {@code index.html} listing every page.
     *
     * @throws IOException if the index cannot be written
     */
    public void finish() throws IOException {
        List<Row> sorted = rows.stream().sorted((a, b) -> a.stem().compareTo(b.stem())).toList();
        long totalRemoved = sorted.stream().mapToLong(Row::componentsRemoved).sum();
        Files.writeString(
                outDir.resolve("index.html"),
                renderHtml(sorted, totalRemoved),
                StandardCharsets.UTF_8);
    }

    private Path panelPath(String panel, String stem) throws IOException {
        Path path = outDir.resolve(panel).resolve(stem + ".png");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return path;
    }

    private static void writeOverlay(Path beforePng, Path afterPng, Path overlayPng)
            throws IOException {
        BufferedImage before = ImageIO.read(beforePng.toFile());
        BufferedImage after = ImageIO.read(afterPng.toFile());
        if (before == null || after == null) {
            throw new IOException("could not read panels for overlay: " + overlayPng);
        }
        int width = Math.min(before.getWidth(), after.getWidth());
        int height = Math.min(before.getHeight(), after.getHeight());
        BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int beforeLuma = luma(before.getRGB(x, y));
                int afterLuma = luma(after.getRGB(x, y));
                boolean removed = beforeLuma < LUMA_MIDPOINT && afterLuma >= LUMA_MIDPOINT;
                overlay.setRGB(x, y, removed ? RED : gray(beforeLuma));
            }
        }
        ImageIO.write(overlay, "png", overlayPng.toFile());
    }

    private static int luma(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static int gray(int luma) {
        return (luma << 16) | (luma << 8) | luma;
    }

    private static String stripExtension(String path) {
        int dot = path.lastIndexOf('.');
        int sep = path.lastIndexOf('/');
        return dot > sep ? path.substring(0, dot) : path;
    }

    private record Row(String stem, int componentsRemoved, double removedRatio) {}

    private static String renderHtml(List<Row> rows, long totalRemoved) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">")
                .append("<title>despeckle report</title><style>")
                .append(
                        "body{font-family:system-ui,sans-serif;margin:2rem;background:#111;color:#eee}")
                .append("h1{font-size:1.2rem}table{border-collapse:collapse;width:100%}")
                .append(
                        "th,td{padding:.4rem .6rem;border-bottom:1px solid"
                                + " #333;vertical-align:top}")
                .append("th{text-align:left;font-weight:600;color:#aaa}")
                .append(
                        ".panels{display:grid;grid-template-columns:repeat(3,1fr);gap:.4rem;margin-top:.4rem}")
                .append(".panels img{width:100%;height:auto;background:#fff}")
                .append(".panels figcaption{font-size:.75rem;color:#888;text-align:center}")
                .append(".stem{font-family:ui-monospace,monospace;font-size:.9rem}")
                .append(".warn{color:#f66}</style></head><body>")
                .append("<h1>despeckle report &mdash; ")
                .append(rows.size())
                .append(" page(s), ")
                .append(totalRemoved)
                .append(" component(s) removed</h1><table>")
                .append("<tr><th>page</th><th>removed</th><th>black&nbsp;cut</th>")
                .append("<th>before / overlay / after</th></tr>");
        for (Row row : rows) {
            String stem = escape(row.stem());
            int pct = (int) Math.round(row.removedRatio() * 100);
            html.append("<tr><td class=\"stem\">")
                    .append(stem)
                    .append("</td><td>")
                    .append(row.componentsRemoved())
                    .append("</td><td")
                    .append(pct >= OVER_REMOVAL_WARN_PERCENT ? " class=\"warn\"" : "")
                    .append('>')
                    .append(pct)
                    .append("%</td><td><div class=\"panels\">")
                    .append(figure("before", stem))
                    .append(figure("overlay", stem))
                    .append(figure("after", stem))
                    .append("</div></td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private static String figure(String panel, String stem) {
        return "<figure><img src=\""
                + panel
                + "/"
                + stem
                + ".png\" loading=\"lazy\"><figcaption>"
                + panel
                + "</figcaption></figure>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
