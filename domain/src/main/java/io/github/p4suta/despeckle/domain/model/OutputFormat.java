package io.github.p4suta.despeckle.domain.model;

import org.jspecify.annotations.Nullable;

/** Selects the on-disk format for cleaned pages. */
public enum OutputFormat {
    /** Keep the input file's format (and extension). */
    SAME,
    /** Write every page as binary PBM (P4). */
    PBM,
    /** Write every page as PNG. */
    PNG,
    /** Write every page as CCITT Group-4 TIFF (1 bpp, lossless) — the pipeline's intermediate. */
    TIFF;

    /** The output file extension, or {@code null} to keep the input's. */
    public @Nullable String extension() {
        return switch (this) {
            case SAME -> null;
            case PBM -> "pbm";
            case PNG -> "png";
            case TIFF -> "tif";
        };
    }
}
