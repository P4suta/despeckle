package io.github.p4suta.despeckle.report;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the per-page overlay panels (each removed pixel painted red over the original) into a
 * single animated WebP "flip-book", so scrubbing the corpus shows the cleaned-up dust blink across
 * the pages at a glance. WebP keeps the artifact small, but neither the JDK nor Leptonica can write
 * animated WebP, so this shells out to libwebp's {@code img2webp}. If that tool is not installed
 * the flip-book is skipped with a warning and the rest of the report is unaffected.
 *
 * <p>The despeckle analogue of {@code register}'s page flip-book: there the frames are the
 * registered pages, here they are the overlays, which is where despeckle's "what changed" lives.
 *
 * <p>The {@code img2webp} binary is taken from {@code -Ddespeckle.img2webp.path} when set,
 * otherwise {@code img2webp} on the {@code PATH}.
 */
final class Flipbook {

    private static final Logger LOG = LoggerFactory.getLogger(Flipbook.class);

    /** Frames taller than this are downscaled; the flip-book is a preview, not an archival copy. */
    private static final int MAX_FRAME_HEIGHT = 1000;

    /**
     * Cap the frame count so a long book stays a small artifact; pages are sampled evenly past it.
     */
    private static final int MAX_FRAMES = 300;

    /** Per-frame display duration in milliseconds. */
    private static final int DELAY_MS = 150;

    private static final long TIMEOUT_SECONDS = 300;

    private Flipbook() {}

    /**
     * Build {@code dir/flipbook.webp} from the overlay panels, in reading order.
     *
     * @param dir the report root
     * @param overlays the overlay PNG paths, in reading order
     * @return {@code true} if {@code flipbook.webp} was written
     * @throws IOException if frame preparation or filesystem work fails
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
        Path framesDir = Files.createTempDirectory(dir, ".flipbook-frames-");
        try {
            List<Path> frames = prepareFrames(overlays, stride, framesDir);
            if (frames.isEmpty()) {
                return false;
            }
            return runImg2webp(frames, dir.resolve("flipbook.webp"));
        } finally {
            deleteRecursively(framesDir);
        }
    }

    /**
     * The frame paths to feed {@code img2webp}: a frame short enough to keep is passed through
     * verbatim (no re-encode), a tall one is downscaled into {@code framesDir}. A frame that cannot
     * be read is dropped with a warning rather than failing the whole book.
     */
    private static List<Path> prepareFrames(List<Path> overlays, int stride, Path framesDir)
            throws IOException {
        List<Path> frames = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < overlays.size(); i += stride) {
            Path overlay = overlays.get(i);
            int height = peekHeight(overlay);
            if (height >= 0 && height <= MAX_FRAME_HEIGHT) {
                frames.add(overlay);
            } else {
                Path scaled = downscale(overlay, framesDir, n++);
                if (scaled != null) {
                    frames.add(scaled);
                }
            }
        }
        return frames;
    }

    /** The pixel height of a PNG read from its header alone, or {@code -1} if it cannot be read. */
    private static int peekHeight(Path png) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(png.toFile())) {
            if (in == null) {
                return -1;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return -1;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static @Nullable Path downscale(Path overlay, Path framesDir, int n)
            throws IOException {
        BufferedImage src = ImageIO.read(overlay.toFile());
        if (src == null) {
            LOG.warn("could not read overlay {} for the flip-book; skipping that frame", overlay);
            return null;
        }
        int width = Math.max(1, (int) ((long) src.getWidth() * MAX_FRAME_HEIGHT / src.getHeight()));
        BufferedImage dst = new BufferedImage(width, MAX_FRAME_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, width, MAX_FRAME_HEIGHT, null);
        } finally {
            g.dispose();
        }
        Path frame = framesDir.resolve(String.format(Locale.ROOT, "frame%05d.png", n));
        ImageIO.write(dst, "png", frame.toFile());
        return frame;
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

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(Flipbook::deleteQuietly);
        } catch (IOException e) {
            LOG.warn("could not clean up flip-book frames in {}: {}", dir, e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("could not delete {}: {}", path, e.getMessage());
        }
    }
}
