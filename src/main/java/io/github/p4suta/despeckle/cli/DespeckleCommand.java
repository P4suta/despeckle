package io.github.p4suta.despeckle.cli;

import io.github.p4suta.despeckle.core.OutputFormat;
import io.github.p4suta.despeckle.core.ProcessOptions;
import io.github.p4suta.despeckle.runner.Runner;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Command-line front end: parse arguments, build a {@link Runner.Config}, run. */
@Command(
        name = "despeckle",
        mixinStandardHelpOptions = true,
        versionProvider = DespeckleCommand.ManifestVersion.class,
        description = "Remove scanner dust from bitonal Japanese-novel scans.")
public final class DespeckleCommand implements Callable<Integer> {

    // inputDir/outputDir are required positionals: picocli always assigns them
    // before call() runs, so they are effectively non-null despite no initializer.
    @SuppressWarnings("NullAway.Init")
    @Parameters(index = "0", description = "Directory of bitonal page images (read recursively).")
    private Path inputDir;

    @SuppressWarnings("NullAway.Init")
    @Parameters(
            index = "1",
            description = "Directory to write cleaned images into (mirrors input).")
    private Path outputDir;

    @Option(names = "--report", description = "Write a before/overlay/after HTML report here.")
    private @Nullable Path reportDir;

    @Option(
            names = {"-j", "--jobs"},
            description = "Worker threads (default: available processors).")
    private int jobs = Runtime.getRuntime().availableProcessors();

    @Option(
            names = "--format",
            description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).",
            showDefaultValue = Visibility.ALWAYS)
    private OutputFormat format = OutputFormat.SAME;

    @Option(
            names = "--glob",
            description = "Glob for input file names (default: ${DEFAULT-VALUE}).",
            showDefaultValue = Visibility.ALWAYS)
    private String glob = "*.{pbm,png,tiff,tif}";

    @Option(names = "--force", description = "Overwrite a non-empty output directory.")
    private boolean force;

    @Option(
            names = "--dpi",
            description =
                    "Scan resolution, used to size the speck filter. Default: each page's embedded"
                            + " resolution, falling back to "
                            + ProcessOptions.DEFAULT_DPI
                            + " when the image carries none.")
    private @Nullable Integer dpi;

    @Option(
            names = "--speck-size",
            description = "Override the speck size in pixels (default: dpi/100).")
    private @Nullable Integer speckSize;

    // Hole-filling and the isolated-dust pass are both on by default; each takes
    // an explicit opt-out. picocli's `negatable` inverts a default-true boolean
    // (--no-x reads as on), so the on/off intent is spelled out with two plain
    // flags instead, combined in call() as `optIn || !optOut`.
    @Option(names = "--fill-holes", description = "Fill pin-holes inside strokes (on by default).")
    private boolean fillHolesOptIn;

    @Option(names = "--no-fill-holes", description = "Disable pin-hole filling.")
    private boolean fillHolesOptOut;

    @Option(
            names = "--remove-isolated-dust",
            description =
                    "Remove isolated specks on clean background (on by default). Punctuation,"
                        + " dakuten and ruby always hug a glyph, so they are kept; only specks out"
                        + " in the margins are dropped.")
    private boolean removeIsolatedDustOptIn;

    @Option(names = "--no-remove-isolated-dust", description = "Disable the isolated-dust pass.")
    private boolean removeIsolatedDustOptOut;

    @Option(
            names = "--isolated-dust-size",
            description =
                    "Max size (px) of an isolated speck to remove; implies"
                            + " --remove-isolated-dust (default: dpi/40).")
    private @Nullable Integer isolatedDustSize;

    @Override
    public Integer call() throws Exception {
        boolean fillHoles = fillHolesOptIn || !fillHolesOptOut;
        boolean removeIsolatedDust = removeIsolatedDustOptIn || !removeIsolatedDustOptOut;
        ProcessOptions options =
                new ProcessOptions(
                        dpi == null ? OptionalInt.empty() : OptionalInt.of(dpi),
                        speckSize == null ? OptionalInt.empty() : OptionalInt.of(speckSize),
                        fillHoles,
                        removeIsolatedDust,
                        isolatedDustSize == null
                                ? OptionalInt.empty()
                                : OptionalInt.of(isolatedDustSize));
        Runner.Config config =
                new Runner.Config(
                        inputDir,
                        outputDir,
                        format,
                        glob,
                        Math.max(1, jobs),
                        force,
                        options,
                        reportDir);
        // Over-removal warnings are surfaced via logging, not a non-zero exit:
        // a clean run and a "look closely at these pages" run both succeed.
        new Runner().run(config);
        return 0;
    }

    /** Supplies {@code --version} from the JAR manifest's Implementation-Version. */
    static final class ManifestVersion implements IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = DespeckleCommand.class.getPackage().getImplementationVersion();
            return new String[] {"despeckle " + (version == null ? "(dev)" : version)};
        }
    }
}
