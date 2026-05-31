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
        int k = options.speckSize();
        try (Pix source = Pix.read(input)) {
            int componentsBefore = source.connectedComponents();
            long blackBefore = source.blackPixels();
            int sourceFormat = source.inputFormat();

            Pix current = source.keepComponentsLargerThan(k);
            try {
                if (options.fillHoles()) {
                    Pix filled = fillHoles(current, k);
                    current.close();
                    current = filled;
                }

                int componentsAfter = current.connectedComponents();
                long blackAfter = current.blackPixels();
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
}
