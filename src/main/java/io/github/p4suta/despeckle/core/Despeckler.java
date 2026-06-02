package io.github.p4suta.despeckle.core;

import java.nio.file.Path;

/**
 * The despeckle pipeline for a single page:
 *
 * <pre>
 *   read → keep components larger than k → (optionally) fill holes → write
 * </pre>
 *
 * <p>All connected-component analysis is delegated to Leptonica's battle-tested {@code
 * pixSelectBySize}; this class only sequences the calls and accounts for what changed. It is
 * stateless and therefore safe to share across threads.
 */
public final class Despeckler {

    /**
     * Process one page from {@code input} to {@code output}.
     *
     * @param input source image path
     * @param output destination image path
     * @param format desired output format
     * @param options despeckle knobs
     * @return what changed on this page
     */
    public ProcessResult process(
            Path input, Path output, OutputFormat format, ProcessOptions options) {
        try (Pix source = Pix.read(input)) {
            int imageDpi = source.resolution();
            int k = options.speckSize(imageDpi);
            int componentsBefore = source.connectedComponents();
            long blackBefore = source.blackPixels();
            int sourceFormat = source.inputFormat();

            Pix current = source.keepComponentsLargerThan(k);
            try {
                if (options.isolatedDustEnabled()) {
                    Pix deisolated =
                            removeIsolatedDust(
                                    current,
                                    options.isolatedDustSize(imageDpi),
                                    options.isolatedDustProximity(imageDpi));
                    current.close();
                    current = deisolated;
                }

                if (options.fillHoles()) {
                    // The thin-stroke threshold: only holes ringed by black thicker than this are
                    // treated as pin-holes. Half the speck size (~3 px at 600 dpi) keeps body
                    // strokes solid while sparing the fine gaps inside small or complex glyphs.
                    int strokeThickness = Math.max(1, Math.round(k / 2.0f));
                    Pix filled = fillHoles(current, k, strokeThickness);
                    current.close();
                    current = filled;
                }

                int componentsAfter = current.connectedComponents();
                long blackAfter = current.blackPixels();
                // Stamp the resolution we honored, so a TIFF/PNG output carries an accurate tag.
                // Only a known resolution is written; an unknown one is left untouched.
                options.resolution(imageDpi).ifPresent(current::setResolution);
                current.write(output, format.toIff(sourceFormat));
                return new ProcessResult(
                        componentsBefore - componentsAfter, blackBefore, blackAfter);
            } finally {
                current.close();
            }
        }
    }

    /**
     * Fill white pin-holes inside black strokes, but only where the surrounding ink is solid. A
     * pin-hole is a small white defect ringed by thick black; the fine white gaps inside small or
     * complex glyphs look the same to a size filter but are ringed by <em>thin</em> strokes, so a
     * plain "fill every small hole" pass crushes them. Opening the page by {@code strokeThickness}
     * keeps only the solid ink; a hole is filled only when it still sits inside that solid mask.
     *
     * @param pix the page
     * @param k the speck size — caps a candidate hole at {@code k} px in either axis
     * @param strokeThickness ink thinner than this is not "solid", so its holes are left alone
     */
    private static Pix fillHoles(Pix pix, int k, int strokeThickness) {
        try (Pix holes = smallHoles(pix, k);
                Pix solid = solidInk(pix, k, strokeThickness);
                Pix fillable = holes.and(solid)) {
            return pix.or(fillable);
        }
    }

    /** The white holes of {@code pix} no larger than {@code k} px in either axis. */
    private static Pix smallHoles(Pix pix, int k) {
        try (Pix inverted = pix.inverted();
                Pix largerWhite = inverted.keepComponentsLargerThan(k)) {
            return inverted.subtract(largerWhite);
        }
    }

    /**
     * {@code pix} reduced to ink thicker than {@code strokeThickness}, with its pin-holes filled.
     */
    private static Pix solidInk(Pix pix, int k, int strokeThickness) {
        try (Pix thick = pix.opened(strokeThickness);
                Pix thickHoles = smallHoles(thick, k)) {
            return thick.or(thickHoles);
        }
    }

    /**
     * Remove specks that are both small enough to be dust (no larger than {@code maxSize} in either
     * axis) and isolated (no kept component within {@code proximity} pixels). Real text is large on
     * at least one axis, so it forms the protected set; punctuation, dakuten and ruby are small but
     * always hug a glyph, so they fall inside that set's neighborhood and are spared. Only specks
     * out on clean background are dropped.
     */
    private static Pix removeIsolatedDust(Pix base, int maxSize, int proximity) {
        try (Pix text = base.keepComponentsLargerThan(maxSize);
                Pix candidates = base.subtract(text);
                Pix textNeighborhood = text.dilated(proximity);
                Pix isolated = candidates.subtract(textNeighborhood)) {
            return base.subtract(isolated);
        }
    }
}
