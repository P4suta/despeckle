package io.github.p4suta.despeckle.port;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Builds {@link Reporter} instances. Because a reporter is stateful (it probes {@code cwebp} and
 * lays out its {@code before}/{@code overlay}/{@code after} directories at creation), the
 * application depends on this factory port rather than constructing reporters directly; the {@code
 * :app} composition root supplies the concrete factory.
 */
public interface ReporterFactory {

    /**
     * Create a reporter rooted at {@code reportDir}.
     *
     * @param reportDir the report root
     * @param flipbook whether to assemble the animated-WebP overlay flip-book at finish
     * @return a ready reporter
     * @throws IOException if the report directory tree cannot be created
     */
    Reporter create(Path reportDir, boolean flipbook) throws IOException;

    /**
     * A pass-through reporter for when reporting is disabled.
     *
     * @return a no-op reporter
     */
    Reporter noOp();
}
