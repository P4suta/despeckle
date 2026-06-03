package io.github.p4suta.despeckle.infrastructure.pdf;

import io.github.p4suta.despeckle.domain.service.PdfListingParser;
import io.github.p4suta.despeckle.infrastructure.process.NativeTools;
import io.github.p4suta.despeckle.port.PdfImageExtractor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * Extracts a PDF's embedded bitonal images as TIFFs by driving {@code pdfimages} — the Java port of
 * the {@code just extract} (pdfimages) + {@code stamp-dpi.py --print} glue. The page range is split
 * across the worker pool (one {@code pdfimages -f/-l} per chunk) with distinct zero-padded {@code
 * page-cNN-} prefixes, so a name sort yields reading order and chunks never collide. The dominant
 * scan DPI is read from {@code pdfimages -list} and passed to the clean step as an explicit DPI, so
 * the extracted TIFFs (which {@code pdfimages} tags at a default 72 dpi) never need re-tagging.
 *
 * <p>The textual {@code pdfinfo}/{@code pdfimages -list} reports are parsed by {@link
 * PdfListingParser}; this adapter only drives the external processes via {@link NativeTools}.
 */
public final class PdfImagesCliExtractor implements PdfImageExtractor {

    /** Creates an extractor that shells out to the {@code pdfimages}/{@code pdfinfo} tools. */
    public PdfImagesCliExtractor() {}

    /** The page count of {@code pdf}, via {@code pdfinfo}. */
    private static int pageCount(Path pdf) throws IOException {
        byte[] out = NativeTools.capture(List.of(NativeTools.pdfinfo(), pdf.toString()), 120);
        return PdfListingParser.parsePageCount(new String(out, StandardCharsets.UTF_8));
    }

    /** The dominant x-ppi across the PDF's images, via {@code pdfimages -list}. */
    @Override
    public int dominantDpi(Path pdf) throws IOException {
        byte[] out =
                NativeTools.capture(List.of(NativeTools.pdfimages(), "-list", pdf.toString()), 120);
        return PdfListingParser.parseDominantDpi(new String(out, StandardCharsets.UTF_8));
    }

    /**
     * Extract all pages of {@code pdf} into {@code outDir} as TIFFs, parallelized over page-range
     * chunks. {@code jobs} bounds both the chunk count and the pool slots used.
     */
    @Override
    public void extract(Path pdf, Path outDir, int jobs, ExecutorService pool) throws IOException {
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
}
