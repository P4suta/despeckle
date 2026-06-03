package io.github.p4suta.despeckle.cli;

import io.github.p4suta.despeckle.core.OutputFormat;
import io.github.p4suta.despeckle.core.ProcessOptions;
import io.github.p4suta.despeckle.runner.Runner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.commons.cli.help.TextHelpAppendable;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line front end: parse arguments, build a {@link Runner.Config}, run.
 *
 * <p>{@link #run(String[])} returns a picocli-compatible exit code (0 success, 2 usage/parse error,
 * 1 runtime error) rather than calling {@code System.exit}, so {@code Main} owns the process exit
 * and the parser can be exercised directly in tests. This is also the one class allowed to write to
 * the standard streams (help/version/usage), which the {@code noStandardStreams} architecture rule
 * carves out by name.
 */
public final class DespeckleCli {

    private static final Logger LOG = LoggerFactory.getLogger(DespeckleCli.class);

    static final int EXIT_OK = 0;
    static final int EXIT_RUNTIME_ERROR = 1;
    static final int EXIT_USAGE_ERROR = 2;

    private static final String SYNTAX = "despeckle <INPUT_DIR> <OUTPUT_DIR> [options]";
    private static final String DESCRIPTION =
            "Remove scanner dust from bitonal Japanese-novel scans. To clean PDFs end-to-end"
                + " (pdfimages -> despeckle -> lossless JBIG2), use 'despeckle pipeline <in.pdf>"
                + " <out.pdf>'; a directory there batches every top-level *.pdf. 'despeckle topdf"
                + " <image-dir> <out.pdf>' packs already-cleaned pages into a JBIG2 PDF.";

    private final Options options = DespeckleOptions.build();

    /** Parse {@code args}, run the pipeline, and return the process exit code. */
    public int run(String[] args) {
        if (args.length > 0 && "pipeline".equals(args[0])) {
            return new PipelineCli().run(Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length > 0 && "topdf".equals(args[0])) {
            return new TopdfCli().run(Arrays.copyOfRange(args, 1, args.length));
        }

        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(options, args);
        } catch (ParseException e) {
            return usageError(e.getMessage());
        }

        if (cmd.hasOption(DespeckleOptions.HELP)) {
            System.out.println(helpText());
            return EXIT_OK;
        }
        if (cmd.hasOption(DespeckleOptions.VERSION)) {
            System.out.println(versionText());
            return EXIT_OK;
        }

        Parsed parsed;
        try {
            parsed = parseArgs(cmd);
        } catch (ParseException e) {
            return usageError(e.getMessage());
        }

        // The split is deliberate: type/usage problems above are exit 2, while value
        // validation (ProcessOptions) and pipeline I/O below are exit 1.
        try {
            new Runner().run(toConfig(parsed));
            return EXIT_OK;
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("despeckle failed: {}", e.getMessage());
            return EXIT_RUNTIME_ERROR;
        }
    }

    /** Type-converts and validates the parsed command line; failures here are usage errors. */
    Parsed parseArgs(CommandLine cmd) throws ParseException {
        List<String> positionals = cmd.getArgList();
        if (positionals.size() != 2) {
            throw new ParseException(
                    "expected exactly 2 positional arguments <INPUT_DIR> <OUTPUT_DIR>, but got "
                            + positionals.size());
        }
        Path inputDir = Path.of(positionals.get(0));
        Path outputDir = Path.of(positionals.get(1));

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
                        ? parseInt(cmd.getOptionValue(DespeckleOptions.JOBS), DespeckleOptions.JOBS)
                        : Runtime.getRuntime().availableProcessors();

        OutputFormat format =
                cmd.hasOption(DespeckleOptions.FORMAT)
                        ? parseFormat(cmd.getOptionValue(DespeckleOptions.FORMAT))
                        : OutputFormat.SAME;

        String glob =
                cmd.hasOption(DespeckleOptions.GLOB)
                        ? cmd.getOptionValue(DespeckleOptions.GLOB)
                        : DespeckleOptions.DEFAULT_GLOB;

        boolean force = cmd.hasOption(DespeckleOptions.FORCE);

        OptionalInt dpi = optionalInt(cmd, DespeckleOptions.DPI);
        OptionalInt speckSize = optionalInt(cmd, DespeckleOptions.SPECK_SIZE);
        OptionalInt isolatedDustSize = optionalInt(cmd, DespeckleOptions.ISOLATED_DUST_SIZE);

        // Hole-filling and the isolated-dust pass are on by default; an --x flag opts in,
        // a --no-x flag opts out, mirroring the old `optIn || !optOut` picocli wiring.
        boolean fillHoles =
                cmd.hasOption(DespeckleOptions.FILL_HOLES)
                        || !cmd.hasOption(DespeckleOptions.NO_FILL_HOLES);
        boolean removeIsolatedDust =
                cmd.hasOption(DespeckleOptions.REMOVE_ISOLATED_DUST)
                        || !cmd.hasOption(DespeckleOptions.NO_REMOVE_ISOLATED_DUST);

        return new Parsed(
                inputDir,
                outputDir,
                reportDir,
                flipbook,
                jobs,
                format,
                glob,
                force,
                dpi,
                speckSize,
                isolatedDustSize,
                fillHoles,
                removeIsolatedDust);
    }

    /** Assembles the runner config; {@link ProcessOptions} rejects non-positive sizes here. */
    static Runner.Config toConfig(Parsed parsed) {
        ProcessOptions processOptions =
                new ProcessOptions(
                        parsed.dpi(),
                        parsed.speckSize(),
                        parsed.fillHoles(),
                        parsed.removeIsolatedDust(),
                        parsed.isolatedDustSize());
        return new Runner.Config(
                parsed.inputDir(),
                parsed.outputDir(),
                parsed.format(),
                parsed.glob(),
                Math.max(1, parsed.jobs()),
                parsed.force(),
                processOptions,
                parsed.reportDir(),
                parsed.flipbook());
    }

    private static OptionalInt optionalInt(CommandLine cmd, String optName) throws ParseException {
        if (!cmd.hasOption(optName)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(parseInt(cmd.getOptionValue(optName), optName));
    }

    private static int parseInt(String raw, String optName) throws ParseException {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new ParseException("--" + optName + " expects an integer, but got: " + raw);
        }
    }

    private static OutputFormat parseFormat(String raw) throws ParseException {
        try {
            return OutputFormat.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ParseException(
                    "--format must be one of pbm | png | tiff | same, but got: " + raw);
        }
    }

    private int usageError(@Nullable String message) {
        System.err.println("despeckle: " + message);
        System.err.println("usage: " + SYNTAX);
        System.err.println("Try 'despeckle --help' for more information.");
        return EXIT_USAGE_ERROR;
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

    private static String versionText() {
        String version = DespeckleCli.class.getPackage().getImplementationVersion();
        return "despeckle " + (version == null ? "(dev)" : version);
    }

    /** Parsed, type-converted command-line values, before {@link ProcessOptions} validation. */
    record Parsed(
            Path inputDir,
            Path outputDir,
            @Nullable Path reportDir,
            boolean flipbook,
            int jobs,
            OutputFormat format,
            String glob,
            boolean force,
            OptionalInt dpi,
            OptionalInt speckSize,
            OptionalInt isolatedDustSize,
            boolean fillHoles,
            boolean removeIsolatedDust) {}
}
