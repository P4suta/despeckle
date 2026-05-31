package io.github.p4suta.despeckle.core;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Objects;

/**
 * An owning, {@link AutoCloseable} handle to a Leptonica {@code PIX}.
 *
 * <p>Every native {@code PIX *} that this process allocates is wrapped the instant it is created,
 * so {@link #close()} (which calls {@code pixDestroy}) is the single release path. Use it with
 * try-with-resources; a {@code Pix} must not outlive its {@code close()}.
 *
 * <p>Instances are not thread-safe, but independent {@code Pix} values on different threads are:
 * Leptonica's per-{@code PIX} operations are reentrant and the only process-global state (message
 * severity) is set once at load.
 */
public final class Pix implements AutoCloseable {

    private MemorySegment handle;

    private Pix(MemorySegment handle) {
        this.handle = handle;
    }

    /** Read an image file into a new {@code Pix}. */
    public static Pix read(Path path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom(path.toString());
            MemorySegment raw = Leptonica.pixRead(name);
            if (raw.address() == 0) {
                throw new IllegalArgumentException("Leptonica could not read image: " + path);
            }
            return new Pix(raw);
        }
    }

    private static Pix wrap(MemorySegment raw, String what) {
        if (raw.address() == 0) {
            throw new IllegalStateException("Leptonica returned NULL from " + what);
        }
        return new Pix(raw);
    }

    /** Write this image to {@code path} using the given Leptonica {@code IFF_*} format. */
    public void write(Path path, int format) {
        ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom(path.toString());
            int rc = Leptonica.pixWrite(name, handle, format);
            if (rc != 0) {
                throw new IllegalStateException(
                        "Leptonica pixWrite failed (rc=" + rc + "): " + path);
            }
        }
    }

    /** Convenience: write this image as PNG (used by the report writer). */
    public void writePng(Path path) {
        write(path, Leptonica.IFF_PNG);
    }

    public int width() {
        ensureOpen();
        return Leptonica.pixGetWidth(handle);
    }

    public int height() {
        ensureOpen();
        return Leptonica.pixGetHeight(handle);
    }

    /** The {@code IFF_*} format this image was read from (for {@code --format same}). */
    public int inputFormat() {
        ensureOpen();
        return Leptonica.pixGetInputFormat(handle);
    }

    /** Number of 8-connected foreground (black) components. */
    public int connectedComponents() {
        ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(JAVA_INT);
            Leptonica.pixCountConnComp(handle, Leptonica.CONN_8, count);
            return count.get(JAVA_INT, 0);
        }
    }

    /** Number of foreground (black) pixels set in the image. */
    public long blackPixels() {
        ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(JAVA_INT);
            Leptonica.pixCountPixels(handle, count, MemorySegment.NULL);
            return Integer.toUnsignedLong(count.get(JAVA_INT, 0));
        }
    }

    /**
     * Return a new {@code Pix} keeping only components whose bounding box is larger than {@code k}
     * in <em>either</em> width or height (8-connected).
     *
     * <p>This is the despeckle core. Scanner dust is a few pixels across in both dimensions, so it
     * fails the {@code > k} test on both and is dropped; punctuation, dakuten and ruby — and even a
     * thin vertical stroke, which is tall — clear it on at least one axis and survive. The {@code
     * (IF_EITHER, IF_GT)} polarity expresses the <em>keep</em> condition (verified by the
     * Milestone-0 spike, where the opposite polarity erased a solid block).
     */
    public Pix keepComponentsLargerThan(int k) {
        ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment changed = arena.allocate(JAVA_INT);
            MemorySegment raw =
                    Leptonica.pixSelectBySize(
                            handle,
                            k,
                            k,
                            Leptonica.CONN_8,
                            Leptonica.L_SELECT_IF_EITHER,
                            Leptonica.L_SELECT_IF_GT,
                            changed);
            return wrap(raw, "pixSelectBySize");
        }
    }

    /** Return a new {@code Pix} that is the bitwise inverse of this one. */
    public Pix inverted() {
        ensureOpen();
        return wrap(Leptonica.pixInvert(handle), "pixInvert");
    }

    /** Whether {@code other} is pixel-identical to this image. */
    public boolean pixelsEqual(Pix other) {
        ensureOpen();
        other.ensureOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment same = arena.allocate(JAVA_INT);
            Leptonica.pixEqual(handle, other.handle, same);
            return same.get(JAVA_INT, 0) == 1;
        }
    }

    @Override
    public void close() {
        if (handle == null) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            // pixDestroy takes a PIX **: a slot holding the pointer, nulled on return.
            MemorySegment slot = arena.allocate(ADDRESS);
            slot.set(ADDRESS, 0, handle);
            Leptonica.pixDestroy(slot);
        } finally {
            handle = null;
        }
    }

    private void ensureOpen() {
        Objects.requireNonNull(handle, "Pix has already been closed");
    }
}
