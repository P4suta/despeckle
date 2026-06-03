package io.github.p4suta.despeckle.application;

import io.github.p4suta.despeckle.domain.model.OutputFormat;
import io.github.p4suta.despeckle.domain.model.ProcessOptions;
import io.github.p4suta.despeckle.port.Jbig2Assembler;
import io.github.p4suta.despeckle.port.PdfImageExtractor;
import io.github.p4suta.despeckle.port.PdfLinearizer;
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
 * The end-to-end PDF &rarr; PDF driver: extract the source PDF's bitonal images (the {@link
 * PdfImageExtractor} port over {@code pdfimages}), despeckle them (the corpus {@link
 * DespeckleService}), and repack the cleaned pages as a lossless-JBIG2 PDF (the {@link
 * Jbig2Assembler} port over PDFBox + {@code jbig2}), finished with a {@link PdfLinearizer} pass for
 * Fast Web View. All intermediates live under one temp directory that is removed at the end, so the
 * only inputs/outputs are the two PDFs.
 *
 * <p>This is the pure-Java replacement for the {@code just extract} &rarr; {@code just run} &rarr;
 * {@code just to-pdf} chain (the Python scripts), so the whole pipeline runs in one self-contained
 * command. The four steps are reached only through injected ports, so this service stays free of
 * {@code :infrastructure}.
 */
public final class PdfPipelineService {

    private static final Logger LOG = LoggerFactory.getLogger(PdfPipelineService.class);

    private final PdfImageExtractor extractor;
    private final DespeckleService despeckleService;
    private final Jbig2Assembler assembler;
    private final PdfLinearizer linearizer;

    /**
     * Create a PDF pipeline service over the injected adapters.
     *
     * @param extractor the PDF image-extraction port
     * @param despeckleService the corpus despeckle service that cleans the extracted pages
     * @param assembler the JBIG2 PDF-assembly port
     * @param linearizer the PDF linearization port
     */
    public PdfPipelineService(
            PdfImageExtractor extractor,
            DespeckleService despeckleService,
            Jbig2Assembler assembler,
            PdfLinearizer linearizer) {
        this.extractor = extractor;
        this.despeckleService = despeckleService;
        this.assembler = assembler;
        this.linearizer = linearizer;
    }

    /**
     * One PDF &rarr; PDF run.
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
    public DespeckleService.Summary run(Config config) throws IOException {
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
            DespeckleService.Summary summary;
            try {
                int dpi =
                        config.options().dpi().isPresent()
                                ? config.options().dpi().getAsInt()
                                : extractor.dominantDpi(config.inputPdf());
                LOG.info(
                        "pipeline: {} -> {} at {} dpi", config.inputPdf(), config.outputPdf(), dpi);

                extractor.extract(config.inputPdf(), extracted, config.jobs(), pool);

                DespeckleService.Config clean =
                        new DespeckleService.Config(
                                extracted,
                                cleaned,
                                OutputFormat.TIFF,
                                "*.tif",
                                config.jobs(),
                                true,
                                config.options().withDpi(dpi),
                                config.reportDir(),
                                config.flipbook());
                summary = despeckleService.run(clean);

                assembler.assemble(
                        cleaned,
                        config.outputPdf(),
                        config.inputPdf(),
                        OptionalInt.of(dpi),
                        pool,
                        jbig2Dir);
            } finally {
                pool.shutdown();
            }
            linearizer.linearize(config.outputPdf());
            LOG.info("wrote {}", config.outputPdf());
            return summary;
        } finally {
            deleteRecursively(work);
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
            walk.sorted(Comparator.reverseOrder()).forEach(PdfPipelineService::deleteQuietly);
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
