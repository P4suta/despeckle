package io.github.p4suta.despeckle.port;

import java.io.IOException;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;

/**
 * Packs a directory of cleaned bitonal pages into a lossless-JBIG2 PDF. The abstraction over the
 * {@code jbig2} encoder + PDFBox container; the implementation ({@code
 * infrastructure.pdf.PdfBoxJbig2Assembler}) runs the per-page encodes on the supplied pool.
 */
public interface Jbig2Assembler {

    /**
     * Assemble {@code imageDir}'s cleaned pages into {@code outPdf}.
     *
     * @param imageDir the directory of cleaned bitonal pages (name order is reading order)
     * @param outPdf the lossless-JBIG2 PDF to write
     * @param source a PDF whose Info dict, XMP and version are inherited, or {@code null} for none
     * @param forcedDpi a single DPI to size every page with, or empty to read each image's own
     * @param pool the worker pool the per-page encodes run on
     * @param scratchDir scratch directory for the intermediate per-page JBIG2 streams
     *     (caller-owned)
     * @throws IOException if the directory is empty, a tool fails, or the write fails
     */
    void assemble(
            Path imageDir,
            Path outPdf,
            @Nullable Path source,
            OptionalInt forcedDpi,
            ExecutorService pool,
            Path scratchDir)
            throws IOException;
}
