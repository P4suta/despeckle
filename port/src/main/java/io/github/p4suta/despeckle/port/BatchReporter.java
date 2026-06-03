package io.github.p4suta.despeckle.port;

import io.github.p4suta.despeckle.domain.model.BatchBook;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the top-level batch index listing every book a {@code despeckle pipeline} batch touched.
 * The abstraction over the batch roll-up rendering; the implementation ({@code
 * infrastructure.report.HtmlBatchReporter}) emits an {@code index.html} under {@code reportParent}.
 */
public interface BatchReporter {

    /**
     * Write the batch index for {@code books}.
     *
     * @param reportParent the batch report root
     * @param books every book the batch processed, in reading order
     * @throws IOException if the index cannot be written
     */
    void write(Path reportParent, List<BatchBook> books) throws IOException;

    /**
     * A batch reporter that writes nothing, for when batch reporting is disabled.
     *
     * @return a no-op batch reporter
     */
    static BatchReporter noOp() {
        return (p, b) -> {};
    }
}
