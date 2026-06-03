package io.github.p4suta.despeckle.observability;

/**
 * Process exit codes for the despeckle CLI. The scheme is intentionally small and picocli-flavored,
 * matching what the three CLI front ends ({@code DespeckleCli}, {@code PipelineCli}, {@code
 * TopdfCli}) returned before this constant set was centralized here:
 *
 * <ul>
 *   <li>{@link #OK} (0) — the run completed successfully;
 *   <li>{@link #RUNTIME_ERROR} (1) — a runtime failure such as an {@link java.io.IOException} or
 *       {@link IllegalArgumentException} (value validation, pipeline I/O, or a batch with at least
 *       one failed book);
 *   <li>{@link #USAGE_ERROR} (2) — a usage / argument-parsing problem (a Commons CLI {@code
 *       ParseException}).
 * </ul>
 */
public final class ExitCodes {

    /** Successful run. */
    public static final int OK = 0;

    /** Runtime failure: {@code IOException}, {@code IllegalArgumentException}, or other runtime. */
    public static final int RUNTIME_ERROR = 1;

    /** Usage / argument-parsing failure (Commons CLI {@code ParseException}). */
    public static final int USAGE_ERROR = 2;

    private ExitCodes() {}
}
