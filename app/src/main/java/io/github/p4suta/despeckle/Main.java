package io.github.p4suta.despeckle;

import io.github.p4suta.despeckle.cli.DespeckleCli;
import io.github.p4suta.despeckle.observability.FatalUncaughtHandler;

/** Process entry point. */
public final class Main {

    private Main() {}

    /**
     * CLI entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler(new FatalUncaughtHandler());
        System.exit(new DespeckleCli().run(args));
    }
}
