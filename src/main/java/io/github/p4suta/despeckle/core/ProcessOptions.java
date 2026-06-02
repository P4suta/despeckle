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
 * <p>A second, opt-in pass removes <em>isolated</em> medium specks — components small enough to be
 * dust that sit on clean background, away from any real text. Because punctuation, dakuten and ruby
 * always hug a glyph, they are never isolated and so are never removed by it; this lets the speck
 * size for clean margins be far more aggressive than a global filter safely could.
 *
 * @param dpi an explicit scan resolution, or empty to read each page's embedded resolution
 * @param speckSizePx an explicit speck size in pixels, or empty to derive it from the resolution
 * @param fillHoles whether to fill isolated white pin-holes inside black strokes
 * @param removeIsolatedDust whether to run the isolated-medium-dust pass
 * @param isolatedDustSizePx an explicit max size for an isolated speck, or empty to derive it; a
 *     present value also implies {@code removeIsolatedDust}
 */
public record ProcessOptions(
        OptionalInt dpi,
        OptionalInt speckSizePx,
        boolean fillHoles,
        boolean removeIsolatedDust,
        OptionalInt isolatedDustSizePx) {

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
        if (isolatedDustSizePx.isPresent() && isolatedDustSizePx.getAsInt() <= 0) {
            throw new IllegalArgumentException(
                    "isolatedDustSizePx must be positive: " + isolatedDustSizePx.getAsInt());
        }
    }

    /** Options with the isolated-dust pass off — the common case. */
    public static ProcessOptions of(OptionalInt dpi, OptionalInt speckSizePx, boolean fillHoles) {
        return new ProcessOptions(dpi, speckSizePx, fillHoles, false, OptionalInt.empty());
    }

    /**
     * Default options: auto-detect resolution, derived speck size, hole-filling on, and the
     * isolated-dust pass on (it only clears genuinely isolated specks, never typography).
     */
    public static ProcessOptions defaults() {
        return new ProcessOptions(
                OptionalInt.empty(), OptionalInt.empty(), true, true, OptionalInt.empty());
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

    /** Whether the isolated-medium-dust pass runs for this configuration. */
    public boolean isolatedDustEnabled() {
        return removeIsolatedDust || isolatedDustSizePx.isPresent();
    }

    /**
     * The largest bounding box (in either axis) an isolated speck may have to still be treated as
     * dust. An explicit value wins; otherwise it scales with resolution (~15 px at 600 dpi), kept
     * comfortably below the smallest real glyph so only genuine specks qualify.
     *
     * @param imageDpi the page's embedded resolution, or 0 if none
     * @return the maximum isolated-speck size in pixels
     */
    public int isolatedDustSize(int imageDpi) {
        if (isolatedDustSizePx.isPresent()) {
            return isolatedDustSizePx.getAsInt();
        }
        int effectiveDpi = resolution(imageDpi).orElse(DEFAULT_DPI);
        return Math.max(speckSize(imageDpi) + 1, Math.round(effectiveDpi / 40.0f));
    }

    /**
     * How close (in pixels) a speck must be to real text to be spared. A speck within this distance
     * of a kept component is assumed to belong to it (dakuten, punctuation, ruby) and is preserved;
     * only specks farther than this on clean background are removed. Sized to cover the speck plus
     * the typical gap to its glyph, so a near-text mark is protected as a whole.
     *
     * @param imageDpi the page's embedded resolution, or 0 if none
     * @return the protection radius in pixels
     */
    public int isolatedDustProximity(int imageDpi) {
        return isolatedDustSize(imageDpi) + speckSize(imageDpi);
    }
}
