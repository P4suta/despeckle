package io.github.p4suta.despeckle.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Packs a directory of already-cleaned bitonal pages into a lossless-JBIG2 PDF — the {@code
 * despeckle topdf} back end, and the pure-Java replacement for {@code just to-pdf} ({@code
 * jbig2-pdf.py}). It is the tail of the {@code despeckle <in> <out>} image-mode flow: clean a
 * directory of pages, then roll them into one PDF. Each page keeps its own resolution unless a
 * single {@code --dpi} is forced; a source scan can be supplied to inherit its metadata. Finished
 * with {@code qpdf --linearize}.
 *
 * <p>(The full PDF → PDF path is {@link PdfPipeline}; this is just its repack stage, exposed for
 * the image-mode flow.)
 */
public final class Jbig2Pack {

    private static final Logger LOG = LoggerFactory.getLogger(Jbig2Pack.class);

    /**
     * One image-directory → PDF run.
     *
     * @param imageDir the directory of cleaned bitonal pages
     * @param outPdf the lossless-JBIG2 PDF to write
     * @param source a source scan whose metadata/version is inherited, or {@code null}
     * @param dpi a single DPI to size every page with, or empty to read each image's own
     * @param jobs worker threads
     * @param force whether to overwrite an existing output PDF
     */
    public record Config(
            Path imageDir,
            Path outPdf,
            @Nullable Path source,
            OptionalInt dpi,
            int jobs,
            boolean force) {}

    /**
     * Pack the images.
     *
     * @param config the run configuration
     * @throws IOException on a missing input, a failed external tool, or a write failure
     */
    public void run(Config config) throws IOException {
        if (!Files.isDirectory(config.imageDir())) {
            throw new IOException("input image directory not found: " + config.imageDir());
        }
        Path outParent = config.outPdf().toAbsolutePath().getParent();
        if (outParent != null) {
            Files.createDirectories(outParent);
        }
        if (!config.force() && Files.exists(config.outPdf())) {
            throw new IOException(config.outPdf() + " already exists; pass --force to overwrite");
        }

        Path jb2Dir = createWorkDir(config.outPdf());
        ExecutorService pool = Executors.newFixedThreadPool(config.jobs());
        try {
            Jbig2PdfAssembler.assemble(
                    config.imageDir(),
                    config.outPdf(),
                    config.source(),
                    config.dpi(),
                    pool,
                    jb2Dir);
        } finally {
            pool.shutdown();
            deleteRecursively(jb2Dir);
        }
        Qpdf.linearize(config.outPdf());
        LOG.info("wrote {}", config.outPdf());
    }

    private static Path createWorkDir(Path outPdf) throws IOException {
        Path parent = outPdf.toAbsolutePath().getParent();
        if (parent != null && Files.isDirectory(parent)) {
            return Files.createTempDirectory(parent, ".despeckle-topdf-");
        }
        return Files.createTempDirectory(".despeckle-topdf-");
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(Jbig2Pack::deleteQuietly);
        } catch (IOException e) {
            LOG.warn("could not clean up topdf temp dir {}: {}", dir, e.getMessage());
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
