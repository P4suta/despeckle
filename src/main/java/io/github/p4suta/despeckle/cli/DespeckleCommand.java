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

    @Option(
            names = "--fill-holes",
            negatable = true,
            description = "Fill pin-holes inside strokes (default: on).")
    private boolean fillHoles = true;

    @Override
    public Integer call() throws Exception {
        ProcessOptions options =
                new ProcessOptions(
                        dpi == null ? OptionalInt.empty() : OptionalInt.of(dpi),
                        speckSize == null ? OptionalInt.empty() : OptionalInt.of(speckSize),
                        fillHoles);
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
