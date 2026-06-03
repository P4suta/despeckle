package io.github.p4suta.despeckle.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The pure parsers for {@code pdfinfo} / {@code pdfimages -list} output (no tools required). */
final class PdfListingParserTest {

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
        assertEquals(12, PdfListingParser.parsePageCount(PDFINFO));
    }

    @Test
    void parsePageCountThrowsWhenAbsent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PdfListingParser.parsePageCount("Title: x\nProducer: y\n"));
    }

    @Test
    void parseDominantDpiPicksTheMostCommonXPpi() {
        // Two pages at 300, one at 150 -> 300 wins.
        assertEquals(300, PdfListingParser.parseDominantDpi(LIST));
    }

    @Test
    void parseDominantDpiFallsBackWhenNoImageRows() {
        assertEquals(
                PdfListingParser.DEFAULT_DPI,
                PdfListingParser.parseDominantDpi("header\n----\n(no image rows)\n"));
    }

    // --- Coverage added for the domain floor (not in the original verbatim test) ---

    @Test
    void parsePageCountThrowsWhenThePagesValueIsNotANumber() {
        // Exercises the NumberFormatException -> IllegalArgumentException catch branch.
        assertThrows(
                IllegalArgumentException.class,
                () -> PdfListingParser.parsePageCount("Pages:           many\n"));
    }

    @Test
    void parseDominantDpiResolvesATieToTheFirstValueSeen() {
        // One row at 300 then one at 150: equal counts, so the first-seen 300 must win. This pins
        // the best-selection comparison (> vs >=) in the tally loop.
        String tie =
                """
                page   num  type   width height color comp bpc  enc  interp object ID x-ppi y-ppi size ratio
                --------------------------------------------------------------------------------------------
                   1     0 image    2480  3508  gray    1   1  ccitt  no      7  0   300   300  101K 1.2%
                   2     1 image    1240  1754  gray    1   1  ccitt  no     11  0   150   150   40K 1.0%
                """;
        assertEquals(300, PdfListingParser.parseDominantDpi(tie));
    }

    @Test
    void parseDominantDpiSkipsRowsWithANonNumericXPpiCell() {
        // The lone numeric row (200) wins; the row whose x-ppi cell is non-numeric is skipped via
        // the inner NumberFormatException catch.
        String mixed =
                """
                page   num  type   width height color comp bpc  enc  interp object ID x-ppi y-ppi size ratio
                --------------------------------------------------------------------------------------------
                   1     0 image    2480  3508  gray    1   1  ccitt  no      7  0     -   200  101K 1.2%
                   2     1 image    2480  3508  gray    1   1  ccitt  no     11  0   200   200   99K 1.1%
                """;
        assertEquals(200, PdfListingParser.parseDominantDpi(mixed));
    }

    @Test
    void parseDominantDpiFallsBackWhenTheWinnerRoundsToNonPositive() {
        // The only image row's x-ppi rounds to 0 (Math.round(0.4) == 0), so best <= 0 and the
        // parser falls back to DEFAULT_DPI.
        String zeroPpi =
                """
                page   num  type   width height color comp bpc  enc  interp object ID x-ppi y-ppi size ratio
                --------------------------------------------------------------------------------------------
                   1     0 image    2480  3508  gray    1   1  ccitt  no      7  0   0.4   0.4  101K 1.2%
                """;
        assertEquals(PdfListingParser.DEFAULT_DPI, PdfListingParser.parseDominantDpi(zeroPpi));
    }

    @Test
    void parseDominantDpiSkipsFullWidthRowsThatAreNotImages() {
        // A full-width (>= 13 fields) non-image row (soft-mask) must be skipped by the type check,
        // not the field-count guard. The lone image row's 200 wins, proving the 999 was ignored.
        String withSmask =
                """
                page   num  type   width height color comp bpc  enc  interp object ID x-ppi y-ppi size ratio
                --------------------------------------------------------------------------------------------
                   1     0 smask    2480  3508  gray    1   1  ccitt  no      7  0   999   999  101K 1.2%
                   2     1 image    2480  3508  gray    1   1  ccitt  no     11  0   200   200   99K 1.1%
                """;
        assertEquals(200, PdfListingParser.parseDominantDpi(withSmask));
    }

    @Test
    void parseDominantDpiSkipsNonImageRowsWithTooFewFields() {
        // A short, non-image row (e.g. a stray line) is ignored, leaving no usable data.
        assertEquals(
                PdfListingParser.DEFAULT_DPI,
                PdfListingParser.parseDominantDpi("hdr\n----\n   1   0   smask\n"));
    }
}
