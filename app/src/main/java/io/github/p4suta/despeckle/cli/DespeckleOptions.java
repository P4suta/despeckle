package io.github.p4suta.despeckle.cli;

import io.github.p4suta.despeckle.domain.model.ProcessOptions;
import java.util.OptionalInt;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 * The Commons CLI {@link Options} model and the canonical flag names.
 *
 * <p>Commons CLI does not render default values or completion candidates the way picocli did, so
 * the defaults ({@code same}, the glob, available processors) are spelled out inside each
 * description.
 */
final class DespeckleOptions {

    static final String HELP = "help";
    static final String VERSION = "version";
    static final String REPORT = "report";
    static final String FLIPBOOK = "flipbook";
    static final String SUFFIX = "suffix";
    static final String SOURCE = "source";
    static final String JOBS = "jobs";
    static final String FORMAT = "format";
    static final String GLOB = "glob";
    static final String FORCE = "force";
    static final String DPI = "dpi";
    static final String SPECK_SIZE = "speck-size";
    static final String FILL_HOLES = "fill-holes";
    static final String NO_FILL_HOLES = "no-fill-holes";
    static final String REMOVE_ISOLATED_DUST = "remove-isolated-dust";
    static final String NO_REMOVE_ISOLATED_DUST = "no-remove-isolated-dust";
    static final String ISOLATED_DUST_SIZE = "isolated-dust-size";

    /** Default input-name glob; also the value injected into the {@code --glob} help text. */
    static final String DEFAULT_GLOB = "*.{pbm,png,tiff,tif}";

    private DespeckleOptions() {}

    /** Builds the option model parsed by {@link DespeckleCli}. */
    static Options build() {
        Options options = new Options();
        options.addOption(Option.builder("h").longOpt(HELP).desc("Show this help and exit.").get());
        options.addOption(
                Option.builder("V")
                        .longOpt(VERSION)
                        .desc("Print version information and exit.")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(REPORT)
                        .hasArg()
                        .argName("DIR")
                        .desc("Write a before/overlay/after HTML report here.")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(FLIPBOOK)
                        .desc(
                                "With --report, also assemble the overlays into an animated-WebP"
                                        + " flip-book (needs libwebp's img2webp).")
                        .get());
        options.addOption(
                Option.builder("j")
                        .longOpt(JOBS)
                        .hasArg()
                        .argName("N")
                        .desc("Worker threads (default: available processors).")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(FORMAT)
                        .hasArg()
                        .argName("FMT")
                        .desc("Output format: pbm | png | tiff | same (default: same).")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(GLOB)
                        .hasArg()
                        .argName("PATTERN")
                        .desc("Glob for input file names (default: " + DEFAULT_GLOB + ").")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(FORCE)
                        .desc("Overwrite a non-empty output directory.")
                        .get());
        addCleanKnobs(options);
        return options;
    }

    /**
     * Add the despeckle clean knobs (resolution and the speck/hole/dust filters) shared by both the
     * image-directory front end ({@link DespeckleCli}) and {@code despeckle pipeline} ({@link
     * PipelineCli}), so the two never drift.
     */
    static void addCleanKnobs(Options options) {
        options.addOption(
                Option.builder()
                        .longOpt(DPI)
                        .hasArg()
                        .argName("N")
                        .desc(
                                "Scan resolution, used to size the speck filter. Default: each"
                                        + " page's embedded resolution, falling back to "
                                        + ProcessOptions.DEFAULT_DPI
                                        + " when the image carries none.")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(SPECK_SIZE)
                        .hasArg()
                        .argName("PX")
                        .desc("Override the speck size in pixels (default: dpi/100).")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(FILL_HOLES)
                        .desc("Fill pin-holes inside strokes (on by default).")
                        .get());
        options.addOption(
                Option.builder().longOpt(NO_FILL_HOLES).desc("Disable pin-hole filling.").get());
        options.addOption(
                Option.builder()
                        .longOpt(REMOVE_ISOLATED_DUST)
                        .desc(
                                "Remove isolated specks on clean background (on by default)."
                                    + " Punctuation, dakuten and ruby always hug a glyph, so they"
                                    + " are kept; only specks out in the margins are dropped.")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(NO_REMOVE_ISOLATED_DUST)
                        .desc("Disable the isolated-dust pass.")
                        .get());
        options.addOption(
                Option.builder()
                        .longOpt(ISOLATED_DUST_SIZE)
                        .hasArg()
                        .argName("PX")
                        .desc(
                                "Max size (px) of an isolated speck to remove; implies"
                                        + " --remove-isolated-dust (default: dpi/40).")
                        .get());
    }

    /**
     * Build {@link ProcessOptions} from the parsed clean knobs. Mirrors {@link DespeckleCli}'s own
     * wiring (an {@code --x}/{@code --no-x} pair where the feature is on unless opted out), and
     * lets {@link ProcessOptions} reject non-positive sizes.
     *
     * @throws ParseException if an integer flag is not a number
     */
    static ProcessOptions cleanProcessOptions(CommandLine cmd) throws ParseException {
        OptionalInt dpi = optionalInt(cmd, DPI);
        OptionalInt speckSize = optionalInt(cmd, SPECK_SIZE);
        OptionalInt isolatedDustSize = optionalInt(cmd, ISOLATED_DUST_SIZE);
        boolean fillHoles = cmd.hasOption(FILL_HOLES) || !cmd.hasOption(NO_FILL_HOLES);
        boolean removeIsolatedDust =
                cmd.hasOption(REMOVE_ISOLATED_DUST) || !cmd.hasOption(NO_REMOVE_ISOLATED_DUST);
        return new ProcessOptions(dpi, speckSize, fillHoles, removeIsolatedDust, isolatedDustSize);
    }

    private static OptionalInt optionalInt(CommandLine cmd, String optName) throws ParseException {
        if (!cmd.hasOption(optName)) {
            return OptionalInt.empty();
        }
        String raw = cmd.getOptionValue(optName);
        try {
            return OptionalInt.of(Integer.parseInt(raw.strip()));
        } catch (NumberFormatException e) {
            throw new ParseException("--" + optName + " expects an integer, but got: " + raw);
        }
    }
}
