package io.github.p4suta.despeckle.core;

import java.util.OptionalInt;

/**
 * Per-page despeckle knobs.
 *
 * <p>The single meaningful parameter is the speck size: connected components whose bounding box is
 * at most this many pixels in both width and height are treated as scanner dust. It is derived from
 * the scan resolution ({@code dpi}) unless overridden explicitly via {@code speckSizePx}.
 *
 * @param dpi the scan resolution, used to derive the speck size when it is not given explicitly
 * @param speckSizePx an explicit speck size in pixels, or empty to derive it
 * @param fillHoles whether to fill isolated white pin-holes inside black strokes
 */
public record ProcessOptions(int dpi, OptionalInt speckSizePx, boolean fillHoles) {

    /** Default scan resolution for self-scanned books. */
    public static final int DEFAULT_DPI = 300;

    /** Validates the option values. */
    public ProcessOptions {
        if (dpi <= 0) {
            throw new IllegalArgumentException("dpi must be positive: " + dpi);
        }
        if (speckSizePx.isPresent() && speckSizePx.getAsInt() <= 0) {
            throw new IllegalArgumentException(
                    "speckSizePx must be positive: " + speckSizePx.getAsInt());
        }
    }

    /** Default options: 300 dpi, derived speck size, hole-filling on. */
    public static ProcessOptions defaults() {
        return new ProcessOptions(DEFAULT_DPI, OptionalInt.empty(), true);
    }

    /**
     * The speck-size threshold in pixels. When not set explicitly it scales with resolution: ~3 px
     * at 300 dpi, ~6 px at 600 dpi.
     *
     * @return the threshold in pixels
     */
    public int speckSize() {
        return speckSizePx.orElseGet(() -> Math.max(1, Math.round(dpi / 100.0f)));
    }
}
