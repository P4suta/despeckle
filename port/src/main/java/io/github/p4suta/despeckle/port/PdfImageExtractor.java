package io.github.p4suta.despeckle.port;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/**
 * Extracts a PDF's embedded bitonal images, and reports its dominant scan resolution. The
 * abstraction over the {@code pdfimages}/{@code pdfinfo} drivers; the implementation ({@code
 * infrastructure.pdf.PdfImagesCliExtractor}) splits the page range across the supplied pool, one
 * {@code pdfimages} invocation per chunk.
 */
public interface PdfImageExtractor {

    /**
     * The dominant x-ppi across the PDF's embedded images.
     *
     * @param pdf the source PDF
     * @return the most common rounded scan resolution
     * @throws IOException if the PDF cannot be inspected
     */
    int dominantDpi(Path pdf) throws IOException;

    /**
     * Extract all pages of {@code pdf} into {@code outDir} as TIFFs, parallelized over page-range
     * chunks. {@code jobs} bounds both the chunk count and the pool slots used.
     *
     * @param pdf the source PDF
     * @param outDir the directory the extracted pages are written into
     * @param jobs the maximum number of parallel chunks
     * @param pool the worker pool the per-chunk extractions run on
     * @throws IOException if the PDF cannot be read or a chunk fails
     */
    void extract(Path pdf, Path outDir, int jobs, ExecutorService pool) throws IOException;
}
