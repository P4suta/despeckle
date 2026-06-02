package io.github.p4suta.despeckle.core;

import java.util.OptionalInt;

/**
 * Per-page despeckle knobs.
 *
 * <p>The single meaningful parameter is the speck size: connected components whose bounding box is
 * at most this many pixels in both width and height are treated as scanner dust. It is derived from
 * the scan resolution unless overridden explicitly via {@code speckSizePx}.
 *
 * <p>The resolution itself is resolved per page, in precedence order: an explicit {@code dpi} wins;
 * otherwise the page's own embedded resolution (TIFF/PNG tag) is used; and when neither is known,
 * the filter falls back to {@link #DEFAULT_DPI} but no resolution is asserted on the output.
 *
 * @param dpi an explicit scan resolution, or empty to read each page's embedded resolution
 * @param speckSizePx an explicit speck size in pixels, or empty to derive it from the resolution
 * @param fillHoles whether to fill isolated white pin-holes inside black strokes
 */
public record ProcessOptions(OptionalInt dpi, OptionalInt speckSizePx, boolean fillHoles) {

    /** Resolution assumed for the speck filter when neither a flag nor the image supplies one. */
    public static final int DEFAULT_DPI = 300;

    /** Validates the option values. */
    public ProcessOptions {
        if (dpi.isPresent() && dpi.getAsInt() <= 0) {
            throw new IllegalArgumentException("dpi must be positive: " + dpi.getAsInt());
        }
        if (speckSizePx.isPresent() && speckSizePx.getAsInt() <= 0) {
            throw new IllegalArgumentException(
                    "speckSizePx must be positive: " + speckSizePx.getAsInt());
        }
    }

    /** Default options: auto-detect resolution, derived speck size, hole-filling on. */
    public static ProcessOptions defaults() {
        return new ProcessOptions(OptionalInt.empty(), OptionalInt.empty(), true);
    }

    /**
     * The resolution to honor for a page whose embedded resolution is {@code imageDpi} (0 if the
     * image carries none). An explicit {@code --dpi} wins; otherwise the image's own resolution is
     * used; otherwise it is unknown. This is the value stamped onto the output, so a guessed
     * fallback is deliberately <em>not</em> reported here — only a resolution we actually know.
     *
     * @param imageDpi the page's embedded resolution, or 0 if none
     * @return the resolution to honor, or empty if none is known
     */
    public OptionalInt resolution(int imageDpi) {
        if (dpi.isPresent()) {
            return dpi;
        }
        return imageDpi > 0 ? OptionalInt.of(imageDpi) : OptionalInt.empty();
    }

    /**
     * The speck-size threshold in pixels for a page whose embedded resolution is {@code imageDpi}.
     * An explicit speck size wins; otherwise it scales with the resolved resolution (~3 px at 300
     * dpi, ~6 px at 600 dpi), assuming {@link #DEFAULT_DPI} when nothing is known.
     *
     * @param imageDpi the page's embedded resolution, or 0 if none
     * @return the threshold in pixels
     */
    public int speckSize(int imageDpi) {
        if (speckSizePx.isPresent()) {
            return speckSizePx.getAsInt();
        }
        int effectiveDpi = resolution(imageDpi).orElse(DEFAULT_DPI);
        return Math.max(1, Math.round(effectiveDpi / 100.0f));
    }
}
