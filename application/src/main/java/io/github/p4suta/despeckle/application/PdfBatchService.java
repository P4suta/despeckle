package io.github.p4suta.despeckle.application;

import io.github.p4suta.despeckle.domain.model.BatchBook;
import io.github.p4suta.despeckle.domain.model.BookStatus;
import io.github.p4suta.despeckle.domain.model.ProcessOptions;
import io.github.p4suta.despeckle.domain.service.PdfOutputNaming;
import io.github.p4suta.despeckle.port.BatchReporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link PdfPipelineService} over every {@code *.pdf} directly under an input directory,
 * writing each cleaned result to {@code <outputDir>/<same-name>.pdf}. Files are processed one at a
 * time (each already saturates the cores through its own {@code --jobs} workers),
 * continue-on-error: a book that fails is logged and counted, never aborting the rest. An output
 * that already exists is skipped unless {@code force} is set, so an interrupted batch resumes
 * cheaply. When a report directory is given, each book gets its own report under {@code
 * <reportParent>/<stem>/} and the injected {@link BatchReporter} writes a top-level {@code
 * <reportParent>/index.html} linking them with the run's roll-up.
 */
public final class PdfBatchService {

    private static final Logger LOG = LoggerFactory.getLogger(PdfBatchService.class);

    private final PdfPipelineService pipeline;
    private final BatchReporter batchReporter;

    /**
     * Create a batch service over the injected per-book pipeline and batch reporter.
     *
     * @param pipeline the per-book PDF &rarr; PDF pipeline service
     * @param batchReporter the batch-index reporter port
     */
    public PdfBatchService(PdfPipelineService pipeline, BatchReporter batchReporter) {
        this.pipeline = pipeline;
        this.batchReporter = batchReporter;
    }

    /**
     * One batch run.
     *
     * @param inputDir the directory whose top-level {@code *.pdf} files are the source scans
     * @param outputDir where each cleaned PDF is written (created if absent)
     * @param options despeckle knobs shared by every book (its empty {@code dpi} is resolved per
     *     file)
     * @param jobs worker threads per file
     * @param force regenerate outputs that already exist instead of skipping them
     * @param suffix inserted before each output's {@code .pdf} extension (e.g. {@code _clean} turns
     *     {@code book.pdf} into {@code book_clean.pdf}); empty keeps the input name
     * @param reportParent the parent directory for per-book reports plus the batch index, or {@code
     *     null} for no report
     * @param flipbook whether each book's report assembles the overlay flip-book
     */
    public record Config(
            Path inputDir,
            Path outputDir,
            ProcessOptions options,
            int jobs,
            boolean force,
            String suffix,
            @Nullable Path reportParent,
            boolean flipbook) {}

    /**
     * What the batch did.
     *
     * @param ok books cleaned this run
     * @param skipped books whose output already existed (no {@code force})
     * @param failed books that threw (logged, not fatal)
     * @param totalPages pages cleaned across the {@code ok} books
     * @param totalComponentsRemoved specks removed across the {@code ok} books
     */
    public record Summary(
            int ok, int skipped, int failed, long totalPages, long totalComponentsRemoved) {}

    /**
     * Clean every top-level {@code *.pdf} under {@code inputDir} into {@code outputDir}.
     *
     * @param config the batch configuration
     * @return the run's roll-up
     * @throws IOException if the input cannot be listed or an output directory cannot be created
     */
    public Summary run(Config config) throws IOException {
        List<Path> inputs = listPdfs(config.inputDir());
        if (inputs.isEmpty()) {
            LOG.warn("no PDFs found in {}", config.inputDir());
            return new Summary(0, 0, 0, 0, 0);
        }
        Files.createDirectories(config.outputDir());

        int ok = 0;
        int skipped = 0;
        int failed = 0;
        long totalPages = 0;
        long totalComponentsRemoved = 0;
        List<BatchBook> books = new ArrayList<>(inputs.size());

        for (int i = 0; i < inputs.size(); i++) {
            Path input = inputs.get(i);
            String stem = PdfOutputNaming.stem(input);
            Path output =
                    config.outputDir().resolve(PdfOutputNaming.outputName(input, config.suffix()));
            String name = Objects.requireNonNull(input.getFileName()).toString();
            String tag = "[" + (i + 1) + "/" + inputs.size() + "] " + name;

            @Nullable Path reportDir =
                    config.reportParent() == null ? null : config.reportParent().resolve(stem);

            if (!config.force() && Files.exists(output)) {
                LOG.info("{} skip (exists)", tag);
                skipped++;
                books.add(
                        new BatchBook(name, stem, BookStatus.SKIPPED, 0, 0, hasReport(reportDir)));
                continue;
            }
            LOG.info("{}", tag);
            try {
                DespeckleService.Summary summary =
                        pipeline.run(
                                new PdfPipelineService.Config(
                                        input,
                                        output,
                                        config.options(),
                                        config.jobs(),
                                        config.force(),
                                        reportDir,
                                        config.flipbook()));
                ok++;
                totalPages += summary.pages();
                totalComponentsRemoved += summary.componentsRemoved();
                books.add(
                        new BatchBook(
                                name,
                                stem,
                                BookStatus.OK,
                                summary.pages(),
                                summary.componentsRemoved(),
                                hasReport(reportDir)));
            } catch (IOException | RuntimeException e) {
                // Continue-on-error: one bad scan must not sink the batch. RuntimeException catches
                // an unexpected failure deeper down (e.g. a Leptonica IllegalStateException).
                failed++;
                LOG.warn("{} failed: {}", tag, e.getMessage());
                books.add(new BatchBook(name, stem, BookStatus.FAILED, 0, 0, hasReport(reportDir)));
            }
        }

        if (config.reportParent() != null) {
            batchReporter.write(config.reportParent(), books);
        }
        LOG.info(
                "done: {} ok, {} skipped, {} failed, {} page(s), {} component(s) removed",
                ok,
                skipped,
                failed,
                totalPages,
                totalComponentsRemoved);
        return new Summary(ok, skipped, failed, totalPages, totalComponentsRemoved);
    }

    private static boolean hasReport(@Nullable Path reportDir) {
        return reportDir != null && Files.exists(reportDir.resolve("index.html"));
    }

    /** Top-level {@code *.pdf} files under {@code dir} (case-insensitive), in file-name order. */
    static List<Path> listPdfs(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile)
                    .filter(
                            p -> {
                                Path name = p.getFileName();
                                return name != null
                                        && name.toString()
                                                .toLowerCase(Locale.ROOT)
                                                .endsWith(".pdf");
                            })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }
}
