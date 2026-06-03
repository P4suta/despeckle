package io.github.p4suta.despeckle.infrastructure.report;

import io.github.p4suta.despeckle.domain.model.PageStat;
import io.github.p4suta.despeckle.domain.model.ProcessResult;
import io.github.p4suta.despeckle.infrastructure.leptonica.Pix;
import io.github.p4suta.despeckle.port.Reporter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * Optional before / overlay / after report, plus a corpus-wide diagnostic suite — the {@link
 * Reporter} adapter backed by an HTML page tree.
 *
 * <p>For each page it writes the original and cleaned images plus an overlay that paints every
 * removed pixel red over the original — slimmed to lossless WebP when {@code cwebp} is present,
 * kept as PNG otherwise. At {@link #finish()} it rolls the per-page stats into three corpus
 * artifacts — a removed-pixel {@link RemovedHeatmap heatmap}, a {@link ConvergenceChartRenderer
 * component-convergence chart} and a {@link RemovalChartRenderer per-page removal chart}, each
 * encoded as lossless WebP — and, with {@code --flipbook}, an animated WebP {@link Flipbook} of the
 * overlays. It emits an {@code index.html} tying them together. This is the human eyeballing
 * surface that lets you confirm dust was removed without eating punctuation or ruby. It is
 * read-only with respect to the pipeline.
 *
 * <p>Both {@code inputImage} and {@code outputImage} are file paths on disk; the Leptonica {@link
 * Pix} used to re-read them and the AWT images painted from them never leave a method body, keeping
 * the image stack confined to {@code :infrastructure}.
 */
public final class HtmlReporter implements Reporter {

    private static final int RED = 0xFF0000;
    private static final int LUMA_MIDPOINT = 128;

    private final Path outDir;
    private final boolean flipbook;

    /**
     * The extension every per-page panel is written with — {@code webp}, or {@code png} fallback.
     */
    private final String panelExt;

    private final RemovedHeatmap heatmap = new RemovedHeatmap();
    private final ConcurrentLinkedQueue<PageStat> stats = new ConcurrentLinkedQueue<>();

    /**
     * Construct a reporter over an already-prepared report directory tree. Built through {@link
     * HtmlReporterFactory#create(Path, boolean)} rather than directly, so the {@code cwebp} probe
     * and {@code before}/{@code overlay}/{@code after} directory creation happen exactly once.
     *
     * @param outDir the report root, with its panel sub-directories already created
     * @param flipbook whether to assemble the animated-WebP overlay flip-book at finish
     * @param panelExt the per-page panel extension — {@code webp} or {@code png}
     */
    HtmlReporter(Path outDir, boolean flipbook, String panelExt) {
        this.outDir = outDir;
        this.flipbook = flipbook;
        this.panelExt = panelExt;
    }

    /**
     * Render and record the three panels for one page, folding its removed pixels into the corpus
     * heatmap as it goes. Thread-safe.
     *
     * @param relativeStem page path relative to the input root
     * @param inputImage original page on disk
     * @param outputImage cleaned page on disk
     * @param result the per-page outcome
     * @throws IOException if a panel cannot be written
     */
    @Override
    public void addPage(Path relativeStem, Path inputImage, Path outputImage, ProcessResult result)
            throws IOException {
        String stem = stripExtension(relativeStem.toString());
        Path beforePng = panelPath("before", stem);
        Path afterPng = panelPath("after", stem);
        Path overlayPng = panelPath("overlay", stem);

        try (Pix before = Pix.read(inputImage)) {
            before.writePng(beforePng);
        }
        try (Pix after = Pix.read(outputImage)) {
            after.writePng(afterPng);
        }
        writeOverlayAndAccumulate(beforePng, afterPng, overlayPng);
        if ("webp".equals(panelExt)) {
            // The overlay was just built from the before/after panels, so it is safe to slim now.
            slimToWebp(beforePng);
            slimToWebp(afterPng);
            slimToWebp(overlayPng);
        }

        stats.add(
                new PageStat(
                        stem,
                        result.componentsBefore(),
                        result.componentsAfter(),
                        result.removedBlackPixelRatio()));
    }

    /**
     * Convert a written {@code .png} panel to a sibling lossless {@code .webp}, dropping the PNG.
     */
    private static void slimToWebp(Path png) throws IOException {
        String name = png.toString();
        Path webp = Path.of(name.substring(0, name.length() - ".png".length()) + ".webp");
        if (Webp.encode(png, webp)) {
            Files.deleteIfExists(png);
        }
    }

    /**
     * Write the corpus artifacts and {@code index.html} listing every page.
     *
     * @throws IOException if an artifact cannot be written
     */
    @Override
    public void finish() throws IOException {
        List<PageStat> sorted =
                stats.stream().sorted(Comparator.comparing(PageStat::stem)).toList();
        long totalRemoved = sorted.stream().mapToLong(PageStat::componentsRemoved).sum();

        String heatmapFile = writeArtifact("removed-heatmap", heatmap.render(sorted.size()));
        String convergenceFile =
                writeArtifact("corpus-convergence", ConvergenceChartRenderer.render(sorted));
        String removalFile = writeArtifact("removal-chart", RemovalChartRenderer.render(sorted));

        boolean flipbookWritten = flipbook && writeFlipbook(sorted);

        Files.writeString(
                outDir.resolve("index.html"),
                renderHtml(
                        sorted,
                        totalRemoved,
                        heatmapFile,
                        convergenceFile,
                        removalFile,
                        flipbookWritten ? "flipbook.webp" : null,
                        panelExt),
                StandardCharsets.UTF_8);
    }

    private boolean writeFlipbook(List<PageStat> sorted) throws IOException {
        List<Path> overlays = new ArrayList<>(sorted.size());
        for (PageStat stat : sorted) {
            overlays.add(outDir.resolve("overlay").resolve(stat.stem() + "." + panelExt));
        }
        return Flipbook.write(outDir, overlays);
    }

    /**
     * Write a corpus image, preferring lossless WebP and keeping the PNG only when {@code cwebp} is
     * unavailable. Returns the file name actually written, so the HTML links the real artifact.
     */
    private String writeArtifact(String basename, BufferedImage img) throws IOException {
        Path png = outDir.resolve(basename + ".png");
        if (!ImageIO.write(img, "png", png.toFile())) {
            throw new IOException("no PNG writer available for " + png);
        }
        Path webp = outDir.resolve(basename + ".webp");
        if (Webp.encode(png, webp)) {
            Files.deleteIfExists(png);
            return basename + ".webp";
        }
        return basename + ".png";
    }

    private Path panelPath(String panel, String stem) throws IOException {
        Path path = outDir.resolve(panel).resolve(stem + ".png");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return path;
    }

    /**
     * Build the red-over-grey overlay for one page and, in the same single pass, drop each removed
     * pixel into the calling thread's heatmap histogram.
     *
     * <p>Pixels are pulled a row at a time via the bulk {@code getRGB(x, y, w, 1, ...)} — one call
     * per row, not one per pixel — which is the difference between a fast scan and a glacial one on
     * a multi-megapixel page, while the luma test (and so the set of "removed" pixels) is
     * byte-for-byte the same as the per-pixel form.
     */
    private void writeOverlayAndAccumulate(Path beforePng, Path afterPng, Path overlayPng)
            throws IOException {
        BufferedImage before = ImageIO.read(beforePng.toFile());
        BufferedImage after = ImageIO.read(afterPng.toFile());
        if (before == null || after == null) {
            throw new IOException("could not read panels for overlay: " + overlayPng);
        }
        int width = Math.min(before.getWidth(), after.getWidth());
        int height = Math.min(before.getHeight(), after.getHeight());
        BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        long[] histogram = heatmap.threadHistogram();
        int[] beforeRow = new int[width];
        int[] afterRow = new int[width];
        int[] overlayRow = new int[width];
        for (int y = 0; y < height; y++) {
            before.getRGB(0, y, width, 1, beforeRow, 0, width);
            after.getRGB(0, y, width, 1, afterRow, 0, width);
            for (int x = 0; x < width; x++) {
                int beforeLuma = luma(beforeRow[x]);
                boolean removed = beforeLuma < LUMA_MIDPOINT && luma(afterRow[x]) >= LUMA_MIDPOINT;
                if (removed) {
                    overlayRow[x] = RED;
                    histogram[RemovedHeatmap.binIndex(x, y, width, height)]++;
                } else {
                    overlayRow[x] = gray(beforeLuma);
                }
            }
            overlay.setRGB(0, y, width, 1, overlayRow, 0, width);
        }
        if (!ImageIO.write(overlay, "png", overlayPng.toFile())) {
            throw new IOException("no PNG writer available for " + overlayPng);
        }
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

    private static String renderHtml(
            List<PageStat> rows,
            long totalRemoved,
            String heatmapFile,
            String convergenceFile,
            String removalFile,
            @Nullable String flipbookFile,
            String panelExt) {
        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">")
                .append("<title>despeckle report</title><style>")
                .append(
                        "body{font-family:system-ui,sans-serif;margin:2rem;background:#111;color:#eee}")
                .append("h1{font-size:1.2rem}h2{font-size:1rem;color:#aaa;margin-top:1.5rem}")
                .append("table{border-collapse:collapse;width:100%}")
                .append(
                        "th,td{padding:.4rem .6rem;border-bottom:1px solid"
                                + " #333;vertical-align:top}")
                .append("th{text-align:left;font-weight:600;color:#aaa}")
                .append(
                        ".corpus{display:flex;flex-wrap:wrap;gap:1rem;align-items:flex-start;margin:.6rem"
                            + " 0 1.2rem}")
                .append(
                        ".corpus figure{margin:0}.corpus"
                            + " img{max-width:400px;height:auto;background:#fff;border:1px solid"
                            + " #333}")
                .append(
                        ".corpus"
                            + " figcaption{font-size:.8rem;color:#888;text-align:center;margin-top:.2rem}")
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
                .append(" component(s) removed</h1>")
                .append("<h2>corpus</h2><div class=\"corpus\">")
                .append(corpusFigure(heatmapFile, "removed-pixel heatmap"))
                .append(corpusFigure(convergenceFile, "component convergence (before → after)"))
                .append(corpusFigure(removalFile, "per-page removal"));
        if (flipbookFile != null) {
            html.append(corpusFigure(flipbookFile, "overlay flip-book"));
        }
        html.append("</div><h2>pages</h2><table>")
                .append("<tr><th>page</th><th>removed</th><th>black&nbsp;cut</th>")
                .append("<th>before / overlay / after</th></tr>");
        for (PageStat row : rows) {
            String stem = escape(row.stem());
            int pct = row.removedPercent();
            boolean warn = row.removedRatio() > ProcessResult.OVER_REMOVAL_WARN_RATIO;
            html.append("<tr><td class=\"stem\">")
                    .append(stem)
                    .append("</td><td>")
                    .append(row.componentsRemoved())
                    .append("</td><td")
                    .append(warn ? " class=\"warn\"" : "")
                    .append('>')
                    .append(pct)
                    .append("%</td><td><div class=\"panels\">")
                    .append(figure("before", stem, panelExt))
                    .append(figure("overlay", stem, panelExt))
                    .append(figure("after", stem, panelExt))
                    .append("</div></td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private static String corpusFigure(String file, String caption) {
        return "<figure><img src=\""
                + file
                + "\" loading=\"lazy\"><figcaption>"
                + caption
                + "</figcaption></figure>";
    }

    private static String figure(String panel, String stem, String ext) {
        return "<figure><img src=\""
                + panel
                + "/"
                + stem
                + "."
                + ext
                + "\" loading=\"lazy\"><figcaption>"
                + panel
                + "</figcaption></figure>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
