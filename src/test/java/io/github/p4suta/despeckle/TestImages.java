package io.github.p4suta.despeckle;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Builds tiny synthetic bitonal pages as binary PBM (P4) files, the same format {@code pdftoppm
 * -mono} emits. {@code true} means a black (foreground) pixel; bits are packed MSB-first with each
 * row padded to a byte, matching the PBM P4 specification and Leptonica's 1 bpp layout.
 */
public final class TestImages {

    private TestImages() {}

    /** Write {@code pixels[y][x]} (true = black) to {@code path} as PBM P4. */
    public static void writePbm(Path path, boolean[][] pixels) throws IOException {
        int height = pixels.length;
        int width = height == 0 ? 0 : pixels[0].length;
        int bytesPerRow = (width + 7) / 8;
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(("P4\n" + width + " " + height + "\n").getBytes(StandardCharsets.US_ASCII));
            byte[] row = new byte[bytesPerRow];
            for (boolean[] line : pixels) {
                Arrays.fill(row, (byte) 0);
                for (int x = 0; x < width; x++) {
                    if (line[x]) {
                        row[x >> 3] |= (byte) (0x80 >> (x & 7));
                    }
                }
                out.write(row);
            }
        }
    }

    /** A blank (all-white) canvas. */
    public static boolean[][] blank(int width, int height) {
        return new boolean[height][width];
    }

    /** Paint a solid filled rectangle (inclusive corners) black. */
    public static void fillRect(boolean[][] img, int x0, int y0, int x1, int y1) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                img[y][x] = true;
            }
        }
    }

    /** Set a single black pixel. */
    public static void dot(boolean[][] img, int x, int y) {
        img[y][x] = true;
    }
}
