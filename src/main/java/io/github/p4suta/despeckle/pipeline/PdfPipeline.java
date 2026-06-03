package io.github.p4suta.despeckle.pipeline;

import io.github.p4suta.despeckle.core.OutputFormat;
import io.github.p4suta.despeckle.core.ProcessOptions;
import io.github.p4suta.despeckle.runner.Runner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The end-to-end PDF → PDF driver: extract the source PDF's bitonal images ({@code pdfimages}),
 * despeckle them (the corpus {@link Runner}), and repack the cleaned pages as a lossless-JBIG2 PDF
 * (PDFBox + {@code jbig2}), finished with a {@code qpdf --linearize} pass for Fast Web View. All
 * intermediates live under one temp directory that is removed at the end, so the only
 * inputs/outputs are the two PDFs.
 *
 * <p>This is the pure-Java replacement for the {@code just extract} → {@code just run} → {@code
 * just to-pdf} chain (the Python scripts), so the whole pipeline runs in one self-contained
 * command.
 */
public final class PdfPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(PdfPipeline.class);

    /**
     * One PDF → PDF run.
     *
     * @param inputPdf the source scan PDF
     * @param outputPdf the cleaned lossless-JBIG2 PDF to write
     * @param options despeckle knobs (its empty {@code dpi} is resolved from the scan)
     * @param jobs worker threads
     * @param force whether to overwrite an existing output PDF
     * @param reportDir an optional report directory for this PDF, or {@code null}
     * @param flipbook whether to assemble the overlay flip-book (needs {@code reportDir})
     */
    public record Config(
            Path inputPdf,
            Path outputPdf,
            ProcessOptions options,
            int jobs,
            boolean force,
            @Nullable Path reportDir,
            boolean flipbook) {}

    /**
     * Run the pipeline.
     *
     * @param config the run configuration
     * @return the despeckle summary (pages, components removed) for this book
     * @throws IOException on a missing input, a failed external tool, or a write failure
     */
    public Runner.Summary run(Config config) throws IOException {
        if (!Files.isRegularFile(config.inputPdf())) {
            throw new IOException("input PDF not found: " + config.inputPdf());
        }
        Path outParent = config.outputPdf().toAbsolutePath().getParent();
        if (outParent != null) {
            Files.createDirectories(outParent);
        }
        if (!config.force() && Files.exists(config.outputPdf())) {
            throw new IOException(
                    config.outputPdf() + " already exists; pass --force to overwrite");
        }

        Path work = createWorkDir(config.outputPdf());
        try {
            Path extracted = Files.createDirectories(work.resolve("in"));
            Path cleaned = Files.createDirectories(work.resolve("clean"));
            Path jbig2Dir = Files.createDirectories(work.resolve("jb2"));
            ExecutorService pool = Executors.newFixedThreadPool(config.jobs());
            Runner.Summary summary;
            try {
                int dpi =
                        config.options().dpi().isPresent()
                                ? config.options().dpi().getAsInt()
                                : PdfImagesExtractor.dominantDpi(config.inputPdf());
                LOG.info(
                        "pipeline: {} -> {} at {} dpi", config.inputPdf(), config.outputPdf(), dpi);

                PdfImagesExtractor.extract(config.inputPdf(), extracted, config.jobs(), pool);

                Runner.Config clean =
                        new Runner.Config(
                                extracted,
                                cleaned,
                                OutputFormat.TIFF,
                                "*.tif",
                                config.jobs(),
                                true,
                                config.options().withDpi(dpi),
                                config.reportDir(),
                                config.flipbook());
                summary = new Runner().run(clean);

                Jbig2PdfAssembler.assemble(
                        cleaned, config.outputPdf(), config.inputPdf(), dpi, pool, jbig2Dir);
            } finally {
                pool.shutdown();
            }
            linearize(config.outputPdf());
            LOG.info("wrote {}", config.outputPdf());
            return summary;
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * Linearize the finished PDF for Fast Web View, matching the Python {@code pdfmeta} path. This
     * is a cosmetic container pass — if qpdf is missing or fails, the (valid, non-linearized) PDF
     * is kept and a warning logged.
     */
    private static void linearize(Path pdf) {
        Path tmp = Path.of(pdf + ".linearized");
        try {
            // qpdf exit 3 is "succeeded with warnings": PDFBox's container trips minor qpdf
            // warnings
            // yet the linearized output is valid, so accept 0 and 3; only a hard error (or a
            // missing
            // qpdf) keeps the original. Writing to a sibling temp avoids qpdf's --replace-input
            // backup file (the *.~qpdf-orig left in the output directory).
            int code =
                    NativeTools.exitCode(
                            List.of(
                                    NativeTools.qpdf(),
                                    "--linearize",
                                    pdf.toString(),
                                    tmp.toString()),
                            120);
            if (code == 0 || code == 3) {
                Files.move(tmp, pdf, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(tmp);
                LOG.warn("qpdf --linearize {} exited {}; kept the un-linearized PDF", pdf, code);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            LOG.warn(
                    "could not linearize {} ({}); kept the un-linearized PDF", pdf, e.getMessage());
        }
    }

    private static Path createWorkDir(Path outputPdf) throws IOException {
        Path parent = outputPdf.toAbsolutePath().getParent();
        if (parent != null && Files.isDirectory(parent)) {
            return Files.createTempDirectory(parent, ".despeckle-pipeline-");
        }
        return Files.createTempDirectory(".despeckle-pipeline-");
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(PdfPipeline::deleteQuietly);
        } catch (IOException e) {
            LOG.warn("could not clean up pipeline temp dir {}: {}", dir, e.getMessage());
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
