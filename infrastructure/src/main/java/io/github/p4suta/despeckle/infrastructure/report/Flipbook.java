package io.github.p4suta.despeckle.infrastructure.report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the per-page overlay panels (each removed pixel painted red over the original) into a
 * single animated WebP "flip-book", so scrubbing the corpus shows the cleaned-up dust blink across
 * the pages at a glance. WebP keeps the artifact small, but neither the JDK nor Leptonica can write
 * animated WebP, so this shells out to libwebp's {@code img2webp} — which reads the overlay frames
 * directly whether they are WebP or PNG. If that tool is not installed the flip-book is skipped
 * with a warning and the rest of the report is unaffected.
 *
 * <p>The despeckle analogue of {@code register}'s page flip-book: there the frames are the
 * registered pages, here they are the overlays, which is where despeckle's "what changed" lives.
 *
 * <p>The {@code img2webp} binary is taken from {@code -Ddespeckle.img2webp.path} when set,
 * otherwise {@code img2webp} on the {@code PATH}.
 */
final class Flipbook {

    private static final Logger LOG = LoggerFactory.getLogger(Flipbook.class);

    /**
     * Cap the frame count so a long book stays a small artifact; pages are sampled evenly past it.
     */
    private static final int MAX_FRAMES = 300;

    /** Per-frame display duration in milliseconds. */
    private static final int DELAY_MS = 150;

    private static final long TIMEOUT_SECONDS = 300;

    private Flipbook() {}

    /**
     * Build {@code dir/flipbook.webp} from the overlay panels, in reading order. The frames may be
     * WebP or PNG — {@code img2webp} reads both — so they are passed through verbatim; a long book
     * is evenly sampled down to {@link #MAX_FRAMES}.
     *
     * @param dir the report root
     * @param overlays the overlay panel paths, in reading order
     * @return {@code true} if {@code flipbook.webp} was written
     * @throws IOException if the assembly is interrupted
     */
    static boolean write(Path dir, List<Path> overlays) throws IOException {
        if (overlays.isEmpty()) {
            return false;
        }
        int stride = (overlays.size() + MAX_FRAMES - 1) / MAX_FRAMES;
        if (stride > 1) {
            LOG.info(
                    "flip-book: {} pages exceeds the {}-frame cap; sampling every {} page(s)",
                    overlays.size(),
                    MAX_FRAMES,
                    stride);
        }
        List<Path> frames = new ArrayList<>();
        for (int i = 0; i < overlays.size(); i += stride) {
            frames.add(overlays.get(i));
        }
        return runImg2webp(frames, dir.resolve("flipbook.webp"));
    }

    private static boolean runImg2webp(List<Path> frames, Path webp) throws IOException {
        String bin = System.getProperty("despeckle.img2webp.path", "img2webp");
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add("-loop");
        cmd.add("0");
        cmd.add("-lossless");
        cmd.add("-d");
        cmd.add(String.valueOf(DELAY_MS));
        for (Path frame : frames) {
            cmd.add(frame.toString());
        }
        cmd.add("-o");
        cmd.add(webp.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            LOG.warn(
                    "could not run '{}' ({}); skipping the WebP flip-book — install libwebp's"
                            + " img2webp or set -Ddespeckle.img2webp.path",
                    bin,
                    e.getMessage());
            return false;
        }
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.warn("img2webp timed out after {}s; flip-book not written", TIMEOUT_SECONDS);
                return false;
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("flip-book assembly interrupted", e);
        }
        int code = process.exitValue();
        if (code != 0) {
            LOG.warn("img2webp exited with status {}; flip-book not written", code);
            return false;
        }
        LOG.info("flip-book written to {} ({} frames)", webp, frames.size());
        return true;
    }
}
