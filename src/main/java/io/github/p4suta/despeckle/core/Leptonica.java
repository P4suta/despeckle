package io.github.p4suta.despeckle.core;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The single unsafe island: every Foreign Function &amp; Memory binding to the system Leptonica
 * library lives here. All other classes work in terms of {@link Pix} and never touch a raw {@link
 * MethodHandle} or {@link MemorySegment}.
 *
 * <p>Verified against Leptonica 1.82.0 ({@code liblept.so.5}) on {@code
 * eclipse-temurin:25-jdk-noble}. The constants below are the literal values from the shipped
 * headers ({@code pix.h}, {@code imageio.h}, {@code environ.h}); they are NOT guessed and must be
 * re-confirmed if the pinned Leptonica version ever changes.
 *
 * <p>This is the one class permitted to call FFM's restricted methods ({@code System.load}, {@code
 * Linker.downcallHandle}); the class-level {@code @SuppressWarnings("restricted")} scopes that
 * exemption here, so a stray restricted call anywhere else still fails the {@code -Werror} build.
 */
@SuppressWarnings("restricted")
final class Leptonica {

    private Leptonica() {}

    // ----- size-selection flags (pix.h) -----
    /** Select on width. */
    static final int L_SELECT_WIDTH = 1;

    /** Select on height. */
    static final int L_SELECT_HEIGHT = 2;

    /** Constraint satisfied if either dimension matches. */
    static final int L_SELECT_IF_EITHER = 5;

    /** Constraint satisfied only if both dimensions match. */
    static final int L_SELECT_IF_BOTH = 6;

    /** Relation: keep if value is greater than the threshold. */
    static final int L_SELECT_IF_GT = 2;

    // ----- image file formats (imageio.h) -----
    /** PNG. */
    static final int IFF_PNG = 3;

    /** Uncompressed TIFF. */
    static final int IFF_TIFF = 4;

    /** CCITT Group-4 fax-compressed TIFF (1 bpp). */
    static final int IFF_TIFF_G4 = 8;

    /** Portable aNy Map (PBM/PGM/PPM); a 1 bpp image writes as binary P4. */
    static final int IFF_PNM = 11;

    // ----- message severity (environ.h) -----
    /** Highest severity: suppress all Leptonica diagnostics. */
    static final int L_SEVERITY_NONE = 6;

    // ----- 8-connectivity for connected-component analysis -----
    static final int CONN_8 = 8;

    /** System property to override the resolved Leptonica library path. */
    static final String LIB_PATH_PROPERTY = "despeckle.leptonica.path";

    // Load the library into this process, then look symbols up against the
    // loader. This is the non-deprecated counterpart to libraryLookup, which
    // JDK 25 marks for removal. System.load wants an absolute path, so we
    // resolve the versioned soname across the standard multiarch locations.
    private static final SymbolLookup LEPT = loadLeptonica();
    private static final Linker LINKER = Linker.nativeLinker();

    private static SymbolLookup loadLeptonica() {
        System.load(resolveLibraryPath());
        return SymbolLookup.loaderLookup();
    }

    private static String resolveLibraryPath() {
        String override = System.getProperty(LIB_PATH_PROPERTY);
        if (override != null) {
            return override;
        }
        List<String> candidates = candidatePaths();
        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Leptonica shared library not found; tried "
                        + candidates
                        + " (override with -D"
                        + LIB_PATH_PROPERTY
                        + "=/path/to/liblept.so)");
    }

    private static List<String> candidatePaths() {
        String triplet =
                switch (System.getProperty("os.arch", "")) {
                    case "amd64", "x86_64" -> "x86_64-linux-gnu";
                    case "aarch64", "arm64" -> "aarch64-linux-gnu";
                    default -> null;
                };
        List<String> candidates = new ArrayList<>();
        // The runtime package ships the versioned soname; the -dev package adds
        // the bare symlink. Prefer the versioned name so a runtime-only image
        // (no -dev) still resolves.
        for (String soname : List.of("liblept.so.5", "liblept.so")) {
            if (triplet != null) {
                candidates.add("/usr/lib/" + triplet + "/" + soname);
                candidates.add("/lib/" + triplet + "/" + soname);
            }
            candidates.add("/usr/lib/" + soname);
            candidates.add("/usr/local/lib/" + soname);
        }
        return candidates;
    }

    private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol =
                LEPT.find(name)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Leptonica symbol not found: " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    private static final MethodHandle PIX_READ =
            handle("pixRead", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle PIX_WRITE =
            handle("pixWrite", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle PIX_DESTROY =
            handle("pixDestroy", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle PIX_GET_WIDTH =
            handle("pixGetWidth", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle PIX_GET_HEIGHT =
            handle("pixGetHeight", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle PIX_GET_INPUT_FORMAT =
            handle("pixGetInputFormat", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle PIX_GET_X_RES =
            handle("pixGetXRes", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle PIX_SET_RESOLUTION =
            handle(
                    "pixSetResolution",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle PIX_INVERT =
            handle("pixInvert", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle PIX_SUBTRACT =
            handle("pixSubtract", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle PIX_DILATE_BRICK =
            handle(
                    "pixDilateBrick",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle PIX_EQUAL =
            handle("pixEqual", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle PIX_COUNT_CONN_COMP =
            handle("pixCountConnComp", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle PIX_COUNT_PIXELS =
            handle("pixCountPixels", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle PIX_SELECT_BY_SIZE =
            handle(
                    "pixSelectBySize",
                    FunctionDescriptor.of(
                            ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                            ADDRESS));
    private static final MethodHandle SET_MSG_SEVERITY =
            handle("setMsgSeverity", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    static {
        // Silence Leptonica's stderr chatter once, at class-load time. The
        // previous severity it returns is irrelevant, so we discard it.
        try {
            SET_MSG_SEVERITY.invoke(L_SEVERITY_NONE);
        } catch (Throwable t) {
            throw sneaky("setMsgSeverity", t);
        }
    }

    /** Read an image file, returning the raw {@code PIX *} (0 on failure). */
    static MemorySegment pixRead(MemorySegment filename) {
        try {
            return (MemorySegment) PIX_READ.invoke(filename);
        } catch (Throwable t) {
            throw sneaky("pixRead", t);
        }
    }

    /** Write {@code pix} to {@code filename} in {@code format}; returns 0 on success. */
    static int pixWrite(MemorySegment filename, MemorySegment pix, int format) {
        try {
            return (int) PIX_WRITE.invoke(filename, pix, format);
        } catch (Throwable t) {
            throw sneaky("pixWrite", t);
        }
    }

    /** Free a {@code PIX}; {@code ppix} is a {@code PIX **} slot, nulled on return. */
    static void pixDestroy(MemorySegment ppix) {
        try {
            PIX_DESTROY.invoke(ppix);
        } catch (Throwable t) {
            throw sneaky("pixDestroy", t);
        }
    }

    static int pixGetWidth(MemorySegment pix) {
        try {
            return (int) PIX_GET_WIDTH.invoke(pix);
        } catch (Throwable t) {
            throw sneaky("pixGetWidth", t);
        }
    }

    static int pixGetHeight(MemorySegment pix) {
        try {
            return (int) PIX_GET_HEIGHT.invoke(pix);
        } catch (Throwable t) {
            throw sneaky("pixGetHeight", t);
        }
    }

    static int pixGetInputFormat(MemorySegment pix) {
        try {
            return (int) PIX_GET_INPUT_FORMAT.invoke(pix);
        } catch (Throwable t) {
            throw sneaky("pixGetInputFormat", t);
        }
    }

    /** The image's horizontal resolution in DPI, or 0 if the source carried none. */
    static int pixGetXRes(MemorySegment pix) {
        try {
            return (int) PIX_GET_X_RES.invoke(pix);
        } catch (Throwable t) {
            throw sneaky("pixGetXRes", t);
        }
    }

    /** Set both axes' resolution in DPI so a format that records it writes an accurate tag. */
    static int pixSetResolution(MemorySegment pix, int xres, int yres) {
        try {
            return (int) PIX_SET_RESOLUTION.invoke(pix, xres, yres);
        } catch (Throwable t) {
            throw sneaky("pixSetResolution", t);
        }
    }

    /** Invert {@code src} into a fresh {@code PIX} (the {@code pixd == NULL} path). */
    static MemorySegment pixInvert(MemorySegment src) {
        try {
            return (MemorySegment) PIX_INVERT.invoke(MemorySegment.NULL, src);
        } catch (Throwable t) {
            throw sneaky("pixInvert", t);
        }
    }

    /** {@code s1 AND NOT s2} into a fresh {@code PIX} (the {@code pixd == NULL} path). */
    static MemorySegment pixSubtract(MemorySegment s1, MemorySegment s2) {
        try {
            return (MemorySegment) PIX_SUBTRACT.invoke(MemorySegment.NULL, s1, s2);
        } catch (Throwable t) {
            throw sneaky("pixSubtract", t);
        }
    }

    /** Dilate {@code src} by a {@code hsize x vsize} brick (odd sizes) into a fresh {@code PIX}. */
    static MemorySegment pixDilateBrick(MemorySegment src, int hsize, int vsize) {
        try {
            return (MemorySegment) PIX_DILATE_BRICK.invoke(MemorySegment.NULL, src, hsize, vsize);
        } catch (Throwable t) {
            throw sneaky("pixDilateBrick", t);
        }
    }

    /** Whether two images are pixel-identical; writes 1/0 into {@code psame}. */
    static int pixEqual(MemorySegment a, MemorySegment b, MemorySegment psame) {
        try {
            return (int) PIX_EQUAL.invoke(a, b, psame);
        } catch (Throwable t) {
            throw sneaky("pixEqual", t);
        }
    }

    static int pixCountConnComp(MemorySegment pix, int connectivity, MemorySegment pcount) {
        try {
            return (int) PIX_COUNT_CONN_COMP.invoke(pix, connectivity, pcount);
        } catch (Throwable t) {
            throw sneaky("pixCountConnComp", t);
        }
    }

    static int pixCountPixels(MemorySegment pix, MemorySegment pcount, MemorySegment tab8) {
        try {
            return (int) PIX_COUNT_PIXELS.invoke(pix, pcount, tab8);
        } catch (Throwable t) {
            throw sneaky("pixCountPixels", t);
        }
    }

    /** Returns a new {@code PIX} of the components that satisfy the size constraint. */
    static MemorySegment pixSelectBySize(
            MemorySegment pix,
            int width,
            int height,
            int connectivity,
            int type,
            int relation,
            MemorySegment pchanged) {
        try {
            return (MemorySegment)
                    PIX_SELECT_BY_SIZE.invoke(
                            pix, width, height, connectivity, type, relation, pchanged);
        } catch (Throwable t) {
            throw sneaky("pixSelectBySize", t);
        }
    }

    private static RuntimeException sneaky(String fn, Throwable cause) {
        return new IllegalStateException("Leptonica call failed: " + fn, cause);
    }
}
