package io.github.p4suta.despeckle.observability;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Centralizes the {@link Throwable} → exit-code mapping that the three CLI front ends used to
 * duplicate inline. It mirrors the original contract exactly:
 *
 * <ul>
 *   <li>a Commons CLI {@code ParseException} (usage / argument-parsing error) → {@link
 *       ExitCodes#USAGE_ERROR};
 *   <li>an {@link java.io.IOException}, {@link IllegalArgumentException}, or any other {@link
 *       RuntimeException} (value validation, pipeline I/O) → {@link ExitCodes#RUNTIME_ERROR}.
 * </ul>
 *
 * <p><b>Why no {@code org.apache.commons.cli} import.</b> This module sits in {@code
 * :observability} and must not pull Commons CLI onto its classpath (that dependency is confined to
 * {@code :app}). So a {@code ParseException} cannot be matched with {@code instanceof}. Instead
 * {@link #isParseException(Throwable)} walks the throwable's class hierarchy and compares each
 * {@link Class#getName() fully-qualified class name} against {@value #PARSE_EXCEPTION_FQN}. Walking
 * the super-class chain (rather than checking only the leaf class) keeps the behavior correct for
 * Commons CLI's {@code ParseException} subclasses (e.g. {@code MissingArgumentException}, {@code
 * UnrecognizedOptionException}), which the {@code DefaultParser} can throw and which the old CLIs
 * caught via {@code catch (ParseException e)}.
 */
public final class ExceptionMapper {

    /**
     * Fully-qualified name of the Commons CLI parse-error type, matched by name so this module
     * avoids a compile-time dependency on {@code commons-cli}.
     */
    static final String PARSE_EXCEPTION_FQN = "org.apache.commons.cli.ParseException";

    private ExceptionMapper() {}

    /**
     * Maps {@code throwable} to a process exit code.
     *
     * @param throwable the failure to classify
     * @return {@link ExitCodes#USAGE_ERROR} for a Commons CLI {@code ParseException} (matched by
     *     name), otherwise {@link ExitCodes#RUNTIME_ERROR}
     */
    public static int exitCodeFor(Throwable throwable) {
        return isParseException(throwable) ? ExitCodes.USAGE_ERROR : ExitCodes.RUNTIME_ERROR;
    }

    /**
     * Tests whether {@code throwable} is a Commons CLI {@code ParseException} (or a subclass of
     * one), matched by fully-qualified class name so this module needs no {@code commons-cli}
     * dependency.
     *
     * @param throwable the throwable to inspect
     * @return {@code true} if {@code throwable}'s type or any of its super-classes is named {@value
     *     #PARSE_EXCEPTION_FQN}
     */
    public static boolean isParseException(Throwable throwable) {
        for (Class<?> type = throwable.getClass(); type != null; type = type.getSuperclass()) {
            if (PARSE_EXCEPTION_FQN.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs {@code throwable}'s message at ERROR via the caller-supplied SLF4J logger, using the
     * {@code "{prefix}: {message}"} shape the CLIs emit (e.g. {@code "despeckle failed: ..."}). The
     * message is logged through a parameterized placeholder, never string-concatenated, and a
     * {@code null} {@link Throwable#getMessage() message} is rendered as the throwable's simple
     * class name so the line is never empty.
     *
     * @param log the SLF4J logger to write to (typically the calling CLI's logger)
     * @param prefix a short human prefix identifying the failing command (e.g. {@code "despeckle
     *     failed"})
     * @param throwable the failure whose message is logged
     */
    public static void logError(Logger log, String prefix, Throwable throwable) {
        log.error("{}: {}", prefix, describe(throwable));
    }

    /**
     * Returns {@code throwable}'s message, or its simple class name when the message is {@code
     * null}.
     */
    private static String describe(Throwable throwable) {
        @Nullable String message = throwable.getMessage();
        return message != null ? message : throwable.getClass().getSimpleName();
    }
}
