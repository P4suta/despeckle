package io.github.p4suta.despeckle.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finishes an assembled PDF with a {@code qpdf --linearize} pass for Fast Web View, matching the
 * Python {@code pdfmeta} path. This is a cosmetic container pass: if qpdf is missing or fails the
 * (valid, non-linearized) PDF is kept and a warning logged, so neither the pipeline nor topdf
 * depends on qpdf being present.
 */
final class Qpdf {

    private static final Logger LOG = LoggerFactory.getLogger(Qpdf.class);

    private Qpdf() {}

    /** Linearize {@code pdf} in place (best effort). */
    static void linearize(Path pdf) {
        Path tmp = Path.of(pdf + ".linearized");
        try {
            // qpdf exit 3 is "succeeded with warnings": PDFBox's container trips minor qpdf
            // warnings
            // yet the linearized output is valid, so accept 0 and 3; only a hard error (or a
            // missing
            // qpdf) keeps the original. Writing to a sibling temp avoids qpdf's --replace-input
            // backup file (the *.~qpdf-orig left in the output directory).
            int code =
                    NativeTools.exitCode(
                            List.of(
                                    NativeTools.qpdf(),
                                    "--linearize",
                                    pdf.toString(),
                                    tmp.toString()),
                            120);
            if (code == 0 || code == 3) {
                Files.move(tmp, pdf, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(tmp);
                LOG.warn("qpdf --linearize {} exited {}; kept the un-linearized PDF", pdf, code);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            LOG.warn(
                    "could not linearize {} ({}); kept the un-linearized PDF", pdf, e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("could not delete {}: {}", path, e.getMessage());
        }
    }
}
