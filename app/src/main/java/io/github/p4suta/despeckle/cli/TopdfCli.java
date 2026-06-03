package io.github.p4suta.despeckle.cli;

import io.github.p4suta.despeckle.application.Jbig2PackService;
import io.github.p4suta.despeckle.infrastructure.pdf.PdfBoxJbig2Assembler;
import io.github.p4suta.despeckle.infrastructure.pdf.QpdfLinearizer;
import io.github.p4suta.despeckle.observability.ExceptionMapper;
import io.github.p4suta.despeckle.observability.ExitCodes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
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
 * Front end for {@code despeckle topdf <image-dir> <out.pdf>}: pack a directory of already-cleaned
 * bitonal pages into one lossless-JBIG2 PDF — the tail of the image-mode flow ({@code despeckle
 * <in> <out>} then {@code topdf}), and the pure-Java replacement for {@code just to-pdf}. Each page
 * keeps its own resolution unless {@code --dpi} forces one; {@code --source} inherits a scan's
 * metadata. Like {@link DespeckleCli} / {@link PipelineCli} it owns the standard streams and the
 * same exit-code contract (0 success, 2 usage, 1 runtime).
 */
final class TopdfCli {

    private static final Logger LOG = LoggerFactory.getLogger(TopdfCli.class);

    private static final String SYNTAX = "despeckle topdf <image-dir> <out.pdf> [options]";
    private static final String DESCRIPTION =
            "Pack a directory of cleaned bitonal pages into one lossless-JBIG2 PDF (jbig2 + qpdf"
                    + " --linearize), the pure-Java repack stage of the image-mode flow. Each page"
                    + " keeps its own resolution unless --dpi forces one.";

    private final Options options = buildOptions();

    private static Options buildOptions() {
        Options options = new Options();
        options.addOption(
                Option.builder("h")
                        .longOpt(DespeckleOptions.HELP)
                        .desc("Show this help and exit.")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(DespeckleOptions.DPI)
                        .hasArg()
                        .argName("N")
                        .desc(
                                "Force a single page-size resolution; default: each image's own"
                                        + " tag, else 300.")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(DespeckleOptions.SOURCE)
                        .hasArg()
                        .argName("PDF")
                        .desc("Inherit Info/XMP metadata and PDF version from this source scan.")
                        .get());
        options.addOption(
                Option.builder("j")
                        .longOpt(DespeckleOptions.JOBS)
                        .hasArg()
                        .argName("N")
                        .desc("Worker threads (default: available processors).")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(DespeckleOptions.FORCE)
                        .desc("Overwrite an existing output PDF.")
                        .get());
        return options;
    }

    /** Parse {@code args} (everything after {@code topdf}), run, and return the exit code. */
    int run(String[] args) {
        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(options, args);
        } catch (ParseException e) {
            return usageError(e.getMessage());
        }
        if (cmd.hasOption(DespeckleOptions.HELP)) {
            System.out.println(helpText());
            return ExitCodes.OK;
        }
        try {
            new Jbig2PackService(new PdfBoxJbig2Assembler(), new QpdfLinearizer())
                    .run(parseArgs(cmd));
            return ExitCodes.OK;
        } catch (ParseException e) {
            return usageError(e.getMessage());
        } catch (IOException | IllegalArgumentException e) {
            ExceptionMapper.logError(LOG, "despeckle topdf failed", e);
            return ExitCodes.RUNTIME_ERROR;
        }
    }

    private Jbig2PackService.Config parseArgs(CommandLine cmd) throws ParseException {
        List<String> positionals = cmd.getArgList();
        if (positionals.size() != 2) {
            throw new ParseException(
                    "expected exactly 2 positional arguments <image-dir> <out.pdf>, but got "
                            + positionals.size());
        }
        Path imageDir = Path.of(positionals.get(0));
        Path outPdf = Path.of(positionals.get(1));

        @Nullable Path source =
                cmd.hasOption(DespeckleOptions.SOURCE)
                        ? Path.of(cmd.getOptionValue(DespeckleOptions.SOURCE))
                        : null;

        OptionalInt dpi = OptionalInt.empty();
        if (cmd.hasOption(DespeckleOptions.DPI)) {
            int value = parseInt(cmd.getOptionValue(DespeckleOptions.DPI));
            if (value <= 0) {
                throw new IllegalArgumentException("--dpi must be positive: " + value);
            }
            dpi = OptionalInt.of(value);
        }

        int jobs =
                cmd.hasOption(DespeckleOptions.JOBS)
                        ? Math.max(1, parseInt(cmd.getOptionValue(DespeckleOptions.JOBS)))
                        : Runtime.getRuntime().availableProcessors();
        boolean force = cmd.hasOption(DespeckleOptions.FORCE);

        return new Jbig2PackService.Config(imageDir, outPdf, source, dpi, jobs, force);
    }

    private static int parseInt(String raw) throws ParseException {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new ParseException("expected an integer, but got: " + raw);
        }
    }

    private int usageError(@Nullable String message) {
        System.err.println("despeckle topdf: " + message);
        System.err.println("usage: " + SYNTAX);
        System.err.println("Try 'despeckle topdf --help' for more information.");
        return ExitCodes.USAGE_ERROR;
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
}
