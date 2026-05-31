package io.github.p4suta.despeckle.runner;

import io.github.p4suta.despeckle.core.Despeckler;
import io.github.p4suta.despeckle.core.OutputFormat;
import io.github.p4suta.despeckle.core.ProcessOptions;
import io.github.p4suta.despeckle.core.ProcessResult;
import io.github.p4suta.despeckle.report.Report;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks an input directory, despeckles every matching page across a fixed thread pool, mirrors the
 * directory layout into the output directory, and optionally drives the {@link Report}.
 *
 * <p>This is the only place that touches the filesystem and threads, keeping {@code core} a pure
 * pipeline that a future GUI could reuse unchanged.
 */
public final class Runner {

    private static final Logger LOG = LoggerFactory.getLogger(Runner.class);

    /** A black-pixel removal ratio above this flags a possibly over-cleaned page. */
    private static final double OVER_REMOVAL_WARN_RATIO = 0.03;

    private static final int PROGRESS_EVERY = 25;

    private final Despeckler despeckler = new Despeckler();

    /**
     * Configuration for one despeckle run.
     *
     * @param inputDir directory of source pages (walked recursively)
     * @param outputDir directory to mirror cleaned pages into
     * @param format output format
     * @param glob file-name glob for input selection
     * @param jobs worker thread count
     * @param force whether to overwrite a non-empty output directory
     * @param options despeckle knobs
     * @param reportDir report output directory, or {@code null} for no report
     */
    public record Config(
            Path inputDir,
            Path outputDir,
            OutputFormat format,
            String glob,
            int jobs,
            boolean force,
            ProcessOptions options,
            @Nullable Path reportDir) {}

    /**
     * Aggregate outcome of a run.
     *
     * @param pages number of pages processed
     * @param componentsRemoved total components removed across all pages
     * @param overRemovalWarnings number of pages flagged for possible over-removal
     */
    public record Summary(int pages, long componentsRemoved, int overRemovalWarnings) {}

    /**
     * Execute a run.
     *
     * @param config run configuration
     * @return the aggregate summary
     * @throws IOException on filesystem failure
     */
    public Summary run(Config config) throws IOException {
        prepareOutputDir(config.outputDir(), config.force());

        List<Path> files = collectFiles(config.inputDir(), config.glob());
        if (files.isEmpty()) {
            LOG.warn("no images matched {} under {}", config.glob(), config.inputDir());
            return new Summary(0, 0, 0);
        }
        LOG.info("despeckling {} page(s) with {} thread(s)", files.size(), config.jobs());

        Report report = config.reportDir() == null ? null : Report.create(config.reportDir());

        AtomicInteger done = new AtomicInteger();
        List<PageOutcome> outcomes;
        ExecutorService pool = Executors.newFixedThreadPool(config.jobs());
        try {
            List<Callable<PageOutcome>> tasks = new ArrayList<>(files.size());
            for (Path src : files) {
                tasks.add(
                        () -> {
                            PageOutcome outcome = processOne(src, config, report);
                            int n = done.incrementAndGet();
                            if (n % PROGRESS_EVERY == 0 || n == files.size()) {
                                System.err.printf("  %d/%d%n", n, files.size());
                            }
                            return outcome;
                        });
            }
            outcomes = invokeAll(pool, tasks);
        } finally {
            pool.shutdown();
        }

        if (report != null) {
            report.finish();
        }

        long totalRemoved = 0;
        int warnings = 0;
        for (PageOutcome outcome : outcomes) {
            totalRemoved += outcome.result().componentsRemoved();
            if (outcome.result().removedBlackPixelRatio() > OVER_REMOVAL_WARN_RATIO) {
                warnings++;
                LOG.warn(
                        "possible over-removal on {}: {}% of black pixels removed",
                        outcome.source(),
                        Math.round(outcome.result().removedBlackPixelRatio() * 100));
            }
        }
        LOG.info(
                "done: {} page(s), {} component(s) removed, {} over-removal warning(s)",
                files.size(),
                totalRemoved,
                warnings);
        return new Summary(files.size(), totalRemoved, warnings);
    }

    private record PageOutcome(Path source, ProcessResult result) {}

    private PageOutcome processOne(Path src, Config config, @Nullable Report report) {
        Path dest = mirrorDestination(src, config);
        try {
            Path parent = dest.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ProcessResult result = despeckler.process(src, dest, config.format(), config.options());
            if (report != null) {
                Path stem = config.inputDir().relativize(src);
                report.addPage(stem, src, dest, result);
            }
            return new PageOutcome(src, result);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to process " + src, e);
        }
    }

    private static List<PageOutcome> invokeAll(
            ExecutorService pool, List<Callable<PageOutcome>> tasks) throws IOException {
        List<Future<PageOutcome>> futures;
        try {
            futures = pool.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("despeckle run interrupted", e);
        }
        List<PageOutcome> outcomes = new ArrayList<>(futures.size());
        for (Future<PageOutcome> future : futures) {
            try {
                outcomes.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("despeckle run interrupted", e);
            } catch (ExecutionException e) {
                throw new IOException("page processing failed", e.getCause());
            }
        }
        return outcomes;
    }

    private static void prepareOutputDir(Path dir, boolean force) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> entries = Files.list(dir)) {
                if (entries.findAny().isPresent() && !force) {
                    throw new IOException(
                            "output directory " + dir + " is not empty; pass --force to overwrite");
                }
            }
        } else {
            Files.createDirectories(dir);
        }
    }

    private static List<Path> collectFiles(Path root, String glob) throws IOException {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(
                            p -> {
                                Path name = p.getFileName();
                                return name != null && matcher.matches(name);
                            })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static Path mirrorDestination(Path src, Config config) {
        Path relative = config.inputDir().relativize(src);
        Path dest = config.outputDir().resolve(relative);
        String extension = config.format().extension();
        Path name = dest.getFileName();
        if (extension == null || name == null) {
            return dest;
        }
        return dest.resolveSibling(stripExtension(name.toString()) + "." + extension);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
