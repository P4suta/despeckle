package io.github.p4suta.despeckle.cli;

import io.github.p4suta.despeckle.core.ProcessOptions;
import io.github.p4suta.despeckle.pipeline.PdfBatch;
import io.github.p4suta.despeckle.pipeline.PdfPipeline;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.commons.cli.help.TextHelpAppendable;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Front end for {@code despeckle pipeline <in.pdf> <out.pdf>}: clean a scanned PDF end-to-end
 * (pdfimages → despeckle → lossless JBIG2) in one self-contained step. A directory as the first
 * argument batches every top-level {@code *.pdf} into the output directory. It shares the despeckle
 * clean knobs (and their {@link ProcessOptions} wiring) with {@link DespeckleCli}; like it, this is
 * the only other class allowed to write to {@code System.out}/{@code System.err}, and it returns
 * the same exit-code contract (0 success, 2 usage, 1 runtime / any batch failure).
 */
final class PipelineCli {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineCli.class);

    private static final String SYNTAX =
            "despeckle pipeline <in.pdf> <out.pdf> | <in-dir> <out-dir> [options]";
    private static final String DESCRIPTION =
            "Clean a scanned PDF end-to-end (pdfimages -> despeckle -> lossless JBIG2), all in one"
                + " self-contained step. With two files, <in.pdf> is the source scan and <out.pdf>"
                + " the cleaned result. With a directory as the first argument, every top-level"
                + " *.pdf under <in-dir> is cleaned into <out-dir>/<same-name>.pdf (existing"
                + " outputs are skipped unless --force; one failed book never stops the rest).";

    private final Options options = buildOptions();

    private static Options buildOptions() {
        Options options = new Options();
        options.addOption(
                Option.builder("h")
                        .longOpt(DespeckleOptions.HELP)
                        .desc("Show this help and exit.")
                        .get());
        options.addOption(
                Option.builder("j")
                        .longOpt(DespeckleOptions.JOBS)
                        .hasArg()
                        .argName("N")
                        .desc("Worker threads per book (default: available processors).")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(DespeckleOptions.FORCE)
                        .desc(
                                "Overwrite an existing output PDF; in batch, regenerate existing"
                                        + " ones.")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(DespeckleOptions.SUFFIX)
                        .hasArg()
                        .argName("S")
                        .desc(
                                "Batch: insert <S> before each output's .pdf (e.g. --suffix _clean"
                                        + " writes book.pdf -> book_clean.pdf).")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(DespeckleOptions.REPORT)
                        .hasArg()
                        .argName("DIR")
                        .desc(
                                "Write a per-book HTML report here (in batch, a top-level"
                                    + " index.html links each book's report under <DIR>/<name>/).")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(DespeckleOptions.FLIPBOOK)
                        .desc(
                                "With --report, also assemble the overlay flip-book (needs"
                                        + " img2webp).")
                        .get());
        DespeckleOptions.addCleanKnobs(options);
        return options;
    }

    /** Parse {@code args} (everything after {@code pipeline}), run, and return the exit code. */
    int run(String[] args) {
        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(options, args);
        } catch (ParseException e) {
            return usageError(e.getMessage());
        }
        if (cmd.hasOption(DespeckleOptions.HELP)) {
            System.out.println(helpText());
            return DespeckleCli.EXIT_OK;
        }

        // The split mirrors DespeckleCli: usage/type problems are exit 2 (ParseException), while
        // value validation (ProcessOptions rejects a non-positive size) and pipeline I/O are exit
        // 1.
        try {
            return dispatch(parseArgs(cmd));
        } catch (ParseException e) {
            return usageError(e.getMessage());
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("despeckle pipeline failed: {}", e.getMessage());
            return DespeckleCli.EXIT_RUNTIME_ERROR;
        }
    }

    private int dispatch(Parsed parsed) throws IOException {
        if (PdfBatch.isBatchInput(parsed.input())) {
            PdfBatch.Summary summary =
                    new PdfBatch()
                            .run(
                                    new PdfBatch.Config(
                                            parsed.input(),
                                            parsed.output(),
                                            parsed.options(),
                                            parsed.jobs(),
                                            parsed.force(),
                                            parsed.suffix(),
                                            parsed.reportDir(),
                                            parsed.flipbook()));
            return summary.failed() > 0 ? DespeckleCli.EXIT_RUNTIME_ERROR : DespeckleCli.EXIT_OK;
        }
        new PdfPipeline()
                .run(
                        new PdfPipeline.Config(
                                parsed.input(),
                                parsed.output(),
                                parsed.options(),
                                parsed.jobs(),
                                parsed.force(),
                                parsed.reportDir(),
                                parsed.flipbook()));
        return DespeckleCli.EXIT_OK;
    }

    /** Type-converts and validates the parsed command line; failures here are usage errors. */
    private Parsed parseArgs(CommandLine cmd) throws ParseException {
        List<String> positionals = cmd.getArgList();
        if (positionals.size() != 2) {
            throw new ParseException(
                    "expected exactly 2 positional arguments <in> <out>, but got "
                            + positionals.size());
        }
        Path input = Path.of(positionals.get(0));
        Path output = Path.of(positionals.get(1));

        Path reportDir =
                cmd.hasOption(DespeckleOptions.REPORT)
                        ? Path.of(cmd.getOptionValue(DespeckleOptions.REPORT))
                        : null;
        boolean flipbook = cmd.hasOption(DespeckleOptions.FLIPBOOK);
        if (flipbook && reportDir == null) {
            throw new ParseException("--flipbook needs --report");
        }

        int jobs =
                cmd.hasOption(DespeckleOptions.JOBS)
                        ? parseJobs(cmd.getOptionValue(DespeckleOptions.JOBS))
                        : Runtime.getRuntime().availableProcessors();
        boolean force = cmd.hasOption(DespeckleOptions.FORCE);
        String suffix = cmd.getOptionValue(DespeckleOptions.SUFFIX, "");

        ProcessOptions options = DespeckleOptions.cleanProcessOptions(cmd);
        return new Parsed(
                input, output, Math.max(1, jobs), force, suffix, reportDir, flipbook, options);
    }

    private static int parseJobs(String raw) throws ParseException {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new ParseException("--jobs expects an integer, but got: " + raw);
        }
    }

    private int usageError(@Nullable String message) {
        System.err.println("despeckle pipeline: " + message);
        System.err.println("usage: " + SYNTAX);
        System.err.println("Try 'despeckle pipeline --help' for more information.");
        return DespeckleCli.EXIT_USAGE_ERROR;
    }

    private String helpText() {
        StringBuilder sink = new StringBuilder();
        HelpFormatter.Builder builder = HelpFormatter.builder();
        builder.setHelpAppendable(new TextHelpAppendable(sink));
        HelpFormatter formatter = builder.get();
        try {
            formatter.printHelp(SYNTAX, DESCRIPTION, options, "", false);
        } catch (IOException e) {
            // Appending to a StringBuilder never actually throws; honor the contract.
            throw new UncheckedIOException(e);
        }
        return sink.toString().stripTrailing();
    }

    /** Parsed, type-converted pipeline command line. */
    private record Parsed(
            Path input,
            Path output,
            int jobs,
            boolean force,
            String suffix,
            @Nullable Path reportDir,
            boolean flipbook,
            ProcessOptions options) {}
}
