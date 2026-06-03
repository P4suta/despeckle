package io.github.p4suta.despeckle.infrastructure.report;

import io.github.p4suta.despeckle.port.Reporter;
import io.github.p4suta.despeckle.port.ReporterFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds {@link HtmlReporter} instances — the {@link ReporterFactory} adapter the {@code :app}
 * composition root supplies to the application. Creating a reporter is stateful (it probes {@code
 * cwebp} once and lays out the {@code before}/{@code overlay}/{@code after} panel directories), so
 * that work is done here, in {@link #create(Path, boolean)}, mirroring the prior {@code
 * Report.create} static factory.
 */
public final class HtmlReporterFactory implements ReporterFactory {

    /** Create a factory. */
    public HtmlReporterFactory() {}

    /**
     * Create the report directory tree and a ready {@link HtmlReporter}.
     *
     * @param reportDir the report root
     * @param flipbook whether to assemble the animated-WebP overlay flip-book at finish
     * @return a ready reporter
     * @throws IOException if the directories cannot be created
     */
    @Override
    public Reporter create(Path reportDir, boolean flipbook) throws IOException {
        for (String panel : List.of("before", "overlay", "after")) {
            Files.createDirectories(reportDir.resolve(panel));
        }
        // Probe cwebp once: when present every per-page panel is slimmed to lossless WebP, else
        // PNG.
        return new HtmlReporter(reportDir, flipbook, Webp.isAvailable() ? "webp" : "png");
    }

    /**
     * A pass-through reporter for when reporting is disabled.
     *
     * @return a no-op reporter
     */
    @Override
    public Reporter noOp() {
        return Reporter.noOp();
    }
}
