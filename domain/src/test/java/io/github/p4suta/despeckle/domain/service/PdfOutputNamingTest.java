package io.github.p4suta.despeckle.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Pure output-naming helpers for the PDF batch. */
final class PdfOutputNamingTest {

    @Test
    void outputNameKeepsTheOriginalNameVerbatimWhenTheSuffixIsEmpty() {
        // Empty suffix preserves the name exactly, extension case included.
        assertEquals("Book.PDF", PdfOutputNaming.outputName(Path.of("in/Book.PDF"), ""));
        assertEquals("book.pdf", PdfOutputNaming.outputName(Path.of("in/book.pdf"), ""));
    }

    @Test
    void outputNameInsertsTheSuffixAndNormalizesTheExtensionToLowerCase() {
        assertEquals(
                "book-clean.pdf",
                PdfOutputNaming.outputName(Path.of("in/book.pdf"), "-clean"),
                "the suffix is inserted before a lower-case .pdf");
        assertEquals(
                "Book-clean.pdf",
                PdfOutputNaming.outputName(Path.of("in/Book.PDF"), "-clean"),
                "a non-empty suffix normalizes the .PDF extension to lower case");
    }

    @Test
    void outputNameThrowsWhenThePathHasNoFileName() {
        // Path.of("/").getFileName() is null, so the requireNonNull guard fires.
        assertThrows(
                NullPointerException.class, () -> PdfOutputNaming.outputName(Path.of("/"), "-x"));
    }

    @Test
    void stemDropsTheDirectoryAndThePdfExtensionCaseInsensitively() {
        assertEquals("book", PdfOutputNaming.stem(Path.of("in/sub/book.pdf")));
        assertEquals("Book", PdfOutputNaming.stem(Path.of("in/Book.PDF")));
    }

    @Test
    void stemThrowsWhenThePathHasNoFileName() {
        assertThrows(NullPointerException.class, () -> PdfOutputNaming.stem(Path.of("/")));
    }

    @Test
    void stripPdfRemovesTheTrailingFourCharacterExtension() {
        assertEquals("book", PdfOutputNaming.stripPdf("book.pdf"));
        assertEquals("Book", PdfOutputNaming.stripPdf("Book.PDF"));
        assertEquals("", PdfOutputNaming.stripPdf(".pdf"), "a bare extension strips to empty");
    }
}
