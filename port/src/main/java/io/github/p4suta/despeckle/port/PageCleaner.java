package io.github.p4suta.despeckle.port;

import io.github.p4suta.despeckle.domain.model.OutputFormat;
import io.github.p4suta.despeckle.domain.model.ProcessOptions;
import io.github.p4suta.despeckle.domain.model.ProcessResult;
import java.nio.file.Path;

/**
 * Despeckles a single page from {@code input} to {@code output}. The sole abstraction over the
 * Leptonica connected-component pipeline; the implementation ({@code
 * infrastructure.leptonica.LeptonicaPageCleaner}) keeps {@code Pix} and the FFM island entirely on
 * its side of this boundary. Implementations are expected to be stateless and safe to share across
 * threads.
 */
public interface PageCleaner {

    /**
     * Process one page from {@code input} to {@code output}.
     *
     * @param input source image path
     * @param output destination image path
     * @param format desired output format
     * @param options despeckle knobs
     * @return what changed on this page
     */
    ProcessResult clean(Path input, Path output, OutputFormat format, ProcessOptions options);
}
