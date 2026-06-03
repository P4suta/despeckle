package io.github.p4suta.despeckle.infrastructure.pdf;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.p4suta.despeckle.port.PdfImageExtractor;
import org.junit.jupiter.api.Test;

/**
 * Adapter-level coverage for {@link PdfImagesCliExtractor}.
 *
 * <p>The original {@code PdfImagesExtractorTest} was made up entirely of pure-parser cases — {@code
 * parsePageCount} and {@code parseDominantDpi} over canned {@code pdfinfo}/{@code pdfimages -list}
 * text. That logic has moved to {@code io.github.p4suta.despeckle.domain.service .PdfListingParser}
 * and is exercised by the {@code :domain} test suite, so it does not belong here.
 *
 * <p>What remains on this adapter — {@link PdfImageExtractor#dominantDpi} and {@link
 * PdfImageExtractor#extract} — only drives the external {@code pdfinfo}/{@code pdfimages} binaries
 * over a real PDF, with no pure branch left to assert against. There is no in-module PDF fixture to
 * feed it (the testFixtures only carry {@code TestImages}), so the meaningful end-to-end exercise
 * of this adapter lives in {@code :app}'s pipeline tests against the sample corpus. All that can be
 * pinned here without a real PDF and the native toolchain is the adapter's construction and port
 * contract.
 */
final class PdfImagesCliExtractorTest {

    @Test
    void constructsAsAPdfImageExtractor() {
        assertInstanceOf(PdfImageExtractor.class, new PdfImagesCliExtractor());
    }
}
