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
                    Pix filled = fillHoles(current, k);
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
     * Fill isolated white pin-holes inside black strokes — the photographic negative of
     * despeckling. In the inverted image a pin-hole is a tiny isolated foreground component, so the
     * same conservative size filter that drops dust drops the hole; inverting back paints it solid.
     */
    private static Pix fillHoles(Pix pix, int k) {
        try (Pix inverted = pix.inverted();
                Pix holesDropped = inverted.keepComponentsLargerThan(k)) {
            return holesDropped.inverted();
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
