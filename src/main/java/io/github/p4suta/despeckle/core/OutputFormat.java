package io.github.p4suta.despeckle.core;

/** Selects the on-disk format for cleaned pages. */
public enum OutputFormat {
    /** Keep the input file's format (and extension). */
    SAME,
    /** Write every page as binary PBM (P4). */
    PBM,
    /** Write every page as PNG. */
    PNG;

    /**
     * Resolve to the Leptonica {@code IFF_*} code to pass to {@code pixWrite}.
     *
     * @param sourceFormat the {@code IFF_*} the page was read from (used by {@link #SAME})
     * @return the Leptonica format code
     */
    int toIff(int sourceFormat) {
        return switch (this) {
            case SAME -> sourceFormat;
            case PBM -> Leptonica.IFF_PNM;
            case PNG -> Leptonica.IFF_PNG;
        };
    }

    /** The output file extension, or {@code null} to keep the input's. */
    public String extension() {
        return switch (this) {
            case SAME -> null;
            case PBM -> "pbm";
            case PNG -> "png";
        };
    }
}
