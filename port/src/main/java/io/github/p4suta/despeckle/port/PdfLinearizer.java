package io.github.p4suta.despeckle.port;

import java.nio.file.Path;

/**
 * Finishes an assembled PDF with a best-effort linearization (Fast Web View) pass. The abstraction
 * over the {@code qpdf --linearize} step; the implementation ({@code
 * infrastructure.pdf.QpdfLinearizer}) is cosmetic — if the tool is missing or fails, the valid
 * non-linearized PDF is kept — so the method declares no checked exception.
 */
public interface PdfLinearizer {

    /**
     * Linearize {@code pdf} in place (best effort).
     *
     * @param pdf the PDF to linearize
     */
    void linearize(Path pdf);

    /**
     * A linearizer that does nothing, for when no Fast-Web-View pass is wanted.
     *
     * @return a no-op linearizer
     */
    static PdfLinearizer noOp() {
        return p -> {};
    }
}
