package io.github.p4suta.despeckle.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The pure parsers for {@code pdfinfo} / {@code pdfimages -list} output (no tools required). */
final class PdfImagesExtractorTest {

    private static final String PDFINFO =
            """
            Title:           Test
            Producer:        despeckle
            Pages:           12
            Page size:       595 x 842 pts
            """;

    private static final String LIST =
            """
            page   num  type   width height color comp bpc  enc  interp object ID x-ppi y-ppi size ratio
            --------------------------------------------------------------------------------------------
               1     0 image    2480  3508  gray    1   1  ccitt  no      7  0   300   300  101K 1.2%
               2     1 image    2480  3508  gray    1   1  ccitt  no     11  0   300   300   99K 1.1%
               3     2 image    1240  1754  gray    1   1  ccitt  no     14  0   150   150   40K 1.0%
            """;

    @Test
    void parsePageCountReadsThePagesLine() {
        assertEquals(12, PdfImagesExtractor.parsePageCount(PDFINFO));
    }

    @Test
    void parsePageCountThrowsWhenAbsent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PdfImagesExtractor.parsePageCount("Title: x\nProducer: y\n"));
    }

    @Test
    void parseDominantDpiPicksTheMostCommonXPpi() {
        // Two pages at 300, one at 150 -> 300 wins.
        assertEquals(300, PdfImagesExtractor.parseDominantDpi(LIST));
    }

    @Test
    void parseDominantDpiFallsBackWhenNoImageRows() {
        assertEquals(
                PdfImagesExtractor.DEFAULT_DPI,
                PdfImagesExtractor.parseDominantDpi("header\n----\n(no image rows)\n"));
    }
}
