package io.github.p4suta.despeckle.domain.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure parsers for the textual reports {@code pdfinfo} and {@code pdfimages -list} emit — the
 * resolution and page-count logic lifted out of the extraction adapter so it can be unit-tested
 * without driving any external process or touching the filesystem.
 */
public final class PdfListingParser {

    /** Fallback when the listing carries no usable resolution (matches {@code stamp-dpi.py}). */
    public static final int DEFAULT_DPI = 300;

    private PdfListingParser() {}

    /**
     * Parse the {@code Pages:} line of {@code pdfinfo} output.
     *
     * @param pdfinfoOutput the full text {@code pdfinfo} printed
     * @return the page count
     * @throws IllegalArgumentException if no parsable {@code Pages:} line is present
     */
    public static int parsePageCount(String pdfinfoOutput) {
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
     *
     * @param listOutput the full text {@code pdfimages -list} printed
     * @return the dominant rounded x-ppi, or {@link #DEFAULT_DPI} when none is usable
     */
    public static int parseDominantDpi(String listOutput) {
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
