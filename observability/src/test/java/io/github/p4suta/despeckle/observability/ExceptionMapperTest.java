package io.github.p4suta.despeckle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Covers the throwable → exit-code classification and the name-based ParseException match. */
final class ExceptionMapperTest {

    /** A subclass of the stand-in ParseException, to exercise the super-class walk. */
    private static final class MissingArgument extends org.apache.commons.cli.ParseException {
        private static final long serialVersionUID = 1L;

        MissingArgument() {
            super("missing argument");
        }
    }

    @Test
    void parseExceptionsAreUsageErrors() {
        assertTrue(
                ExceptionMapper.isParseException(new org.apache.commons.cli.ParseException("bad")));
        assertEquals(
                ExitCodes.USAGE_ERROR,
                ExceptionMapper.exitCodeFor(new org.apache.commons.cli.ParseException("bad")));
    }

    @Test
    void parseExceptionSubclassesAreUsageErrorsToo() {
        // The walk up the super-class chain must catch DefaultParser subclasses like this one.
        assertTrue(ExceptionMapper.isParseException(new MissingArgument()));
        assertEquals(ExitCodes.USAGE_ERROR, ExceptionMapper.exitCodeFor(new MissingArgument()));
    }

    @Test
    void otherThrowablesAreRuntimeErrors() {
        assertFalse(ExceptionMapper.isParseException(new IOException("disk")));
        assertEquals(ExitCodes.RUNTIME_ERROR, ExceptionMapper.exitCodeFor(new IOException("disk")));
        assertEquals(
                ExitCodes.RUNTIME_ERROR,
                ExceptionMapper.exitCodeFor(new IllegalArgumentException("dpi")));
    }

    @Test
    void logErrorHandlesBothMessagedAndMessagelessThrowables() {
        var log = LoggerFactory.getLogger(ExceptionMapperTest.class);
        // Both arms of describe(): a real message, and a null message (rendered as the class name).
        ExceptionMapper.logError(log, "despeckle failed", new IOException("boom"));
        ExceptionMapper.logError(log, "despeckle failed", new IllegalStateException());
    }

    @Test
    void exitCodesAreTheClassicScheme() {
        assertEquals(0, ExitCodes.OK);
        assertEquals(1, ExitCodes.RUNTIME_ERROR);
        assertEquals(2, ExitCodes.USAGE_ERROR);
    }
}
