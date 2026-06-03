package org.apache.commons.cli;

/**
 * Test stand-in for Commons CLI's {@code ParseException}. {@code :observability} deliberately has
 * no {@code commons-cli} dependency (that library is confined to {@code :app}), so {@link
 * io.github.p4suta.despeckle.observability.ExceptionMapper} classifies parse errors by
 * fully-qualified class name. This minimal class reproduces that exact name so the matching — and
 * the super-class walk that handles real subclasses — can be tested here without the real library.
 */
public class ParseException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Create a parse exception.
     *
     * @param message the detail message
     */
    public ParseException(String message) {
        super(message);
    }
}
