package io.github.p4suta.despeckle.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * Extracts a PDF's embedded bitonal images as TIFFs by driving {@code pdfimages} — the Java port of
 * the {@code just extract} (pdfimages) + {@code stamp-dpi.py --print} glue. The page range is split
 * across the worker pool (one {@code pdfimages -f/-l} per chunk) with distinct zero-padded {@code
 * page-cNN-} prefixes, so a name sort yields reading order and chunks never collide. The dominant
 * scan DPI is read from {@code pdfimages -list} and passed to the clean step as an explicit DPI, so
 * the extracted TIFFs (which {@code pdfimages} tags at a default 72 dpi) never need re-tagging.
 */
final class PdfImagesExtractor {

    /** Fallback when the listing carries no usable resolution (matches {@code stamp-dpi.py}). */
    static final int DEFAULT_DPI = 300;

    private PdfImagesExtractor() {}

    /** The page count of {@code pdf}, via {@code pdfinfo}. */
    static int pageCount(Path pdf) throws IOException {
        byte[] out = NativeTools.capture(List.of(NativeTools.pdfinfo(), pdf.toString()), 120);
        return parsePageCount(new String(out, StandardCharsets.UTF_8));
    }

    /** The dominant x-ppi across the PDF's images, via {@code pdfimages -list}. */
    static int dominantDpi(Path pdf) throws IOException {
        byte[] out =
                NativeTools.capture(List.of(NativeTools.pdfimages(), "-list", pdf.toString()), 120);
        return parseDominantDpi(new String(out, StandardCharsets.UTF_8));
    }

    /**
     * Extract all pages of {@code pdf} into {@code outDir} as TIFFs, parallelized over page-range
     * chunks. {@code jobs} bounds both the chunk count and the pool slots used.
     */
    static void extract(Path pdf, Path outDir, int jobs, ExecutorService pool) throws IOException {
        int total = pageCount(pdf);
        int chunks = Math.max(1, Math.min(jobs, total));
        int per = (total + chunks - 1) / chunks;
        String pdfimages = NativeTools.pdfimages();
        List<Callable<Void>> tasks = new ArrayList<>();
        int chunk = 0;
        for (int first = 1; first <= total; first += per) {
            int last = Math.min(first + per - 1, total);
            String prefix =
                    outDir.resolve(String.format(Locale.ROOT, "page-c%03d-", chunk)).toString();
            int from = first;
            int to = last;
            tasks.add(
                    () -> {
                        NativeTools.run(
                                List.of(
                                        pdfimages,
                                        "-tiff",
                                        "-f",
                                        Integer.toString(from),
                                        "-l",
                                        Integer.toString(to),
                                        pdf.toString(),
                                        prefix),
                                600);
                        return null;
                    });
            chunk++;
        }
        NativeTools.awaitAll(pool, tasks);
    }

    /** Parse the {@code Pages:} line of {@code pdfinfo} output. */
    static int parsePageCount(String pdfinfoOutput) {
        for (String line : pdfinfoOutput.split("\n", -1)) {
            if (line.startsWith("Pages:")) {
                String value = line.substring("Pages:".length()).trim();
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("unparsable pdfinfo Pages line: " + line, e);
                }
            }
        }
        throw new IllegalArgumentException("pdfinfo output had no Pages: line");
    }

    /**
     * The most common rounded x-ppi (column 13, 0-based 12) across the {@code image} rows of a
     * {@code pdfimages -list} report, skipping the two header rows. Ties resolve to the first value
     * seen and a non-positive winner falls back to {@link #DEFAULT_DPI} — matching {@code
     * stamp-dpi.py}'s {@code Counter.most_common}.
     */
    static int parseDominantDpi(String listOutput) {
        String[] lines = listOutput.split("\n", -1);
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int i = 2; i < lines.length; i++) {
            String[] fields = lines[i].trim().split("\\s+", -1);
            if (fields.length < 13 || !"image".equals(fields[2])) {
                continue;
            }
            try {
                int ppi = (int) Math.round(Double.parseDouble(fields[12]));
                counts.merge(ppi, 1, Integer::sum);
            } catch (NumberFormatException ignored) {
                // Non-numeric x-ppi cell: skip this row, as stamp-dpi.py does.
            }
        }
        if (counts.isEmpty()) {
            return DEFAULT_DPI;
        }
        int best = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best > 0 ? best : DEFAULT_DPI;
    }
}
