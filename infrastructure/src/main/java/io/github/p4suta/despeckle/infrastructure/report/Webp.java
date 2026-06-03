package io.github.p4suta.despeckle.infrastructure.report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes a still PNG to a lossless WebP via libwebp's {@code cwebp}, so the corpus diagnostics
 * come out as crisp, compact WebP instead of bland PNG — the same WebP family the {@link Flipbook}
 * uses. Charts are hard-edged line art, so {@code -lossless} keeps every pixel exact while still
 * beating PNG on size.
 *
 * <p>Neither the JDK nor the Leptonica binding can write WebP, so this shells out. If {@code cwebp}
 * is not installed the caller keeps the PNG it already wrote — the report degrades to PNG rather
 * than failing. The binary is taken from {@code -Ddespeckle.cwebp.path} when set, otherwise {@code
 * cwebp} on the {@code PATH}.
 */
final class Webp {

    private static final Logger LOG = LoggerFactory.getLogger(Webp.class);

    private static final long TIMEOUT_SECONDS = 120;

    private Webp() {}

    /**
     * Whether {@code cwebp} can be launched, so the report can decide once whether to slim its
     * per-page panels to WebP or keep PNG (instead of trying — and warning — on every page).
     *
     * @return {@code true} if {@code cwebp -version} runs and exits 0
     */
    static boolean isAvailable() {
        String bin = System.getProperty("despeckle.cwebp.path", "cwebp");
        try {
            Process process =
                    new ProcessBuilder(bin, "-version")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Convert {@code png} to {@code webp} losslessly.
     *
     * @param png the existing source PNG
     * @param webp the WebP path to write
     * @return {@code true} if {@code webp} was written; {@code false} if cwebp is unavailable or
     *     failed (in which case the source PNG should be kept)
     * @throws IOException if the encode is interrupted
     */
    static boolean encode(Path png, Path webp) throws IOException {
        String bin = System.getProperty("despeckle.cwebp.path", "cwebp");
        ProcessBuilder pb =
                new ProcessBuilder(
                        bin, "-quiet", "-lossless", png.toString(), "-o", webp.toString());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            LOG.warn(
                    "could not run '{}' ({}); keeping PNG — install libwebp's cwebp or set"
                            + " -Ddespeckle.cwebp.path",
                    bin,
                    e.getMessage());
            return false;
        }
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.warn("cwebp timed out after {}s; keeping PNG for {}", TIMEOUT_SECONDS, webp);
                return false;
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("webp encode interrupted", e);
        }
        int code = process.exitValue();
        if (code != 0) {
            LOG.warn("cwebp exited with status {}; keeping PNG for {}", code, webp);
            return false;
        }
        return true;
    }
}
