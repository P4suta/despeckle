package io.github.p4suta.despeckle.domain.model;

/**
 * The outcome of one book in a {@code despeckle pipeline} batch. Replaces the prior free-form
 * {@code "ok"}/{@code "skipped"}/{@code "failed"} status strings with a closed domain enum.
 */
public enum BookStatus {
    /** The book was cleaned this run. */
    OK,
    /** The book's output already existed, so it was skipped (no {@code force}). */
    SKIPPED,
    /** The book threw and was counted but not fatal to the batch. */
    FAILED
}
