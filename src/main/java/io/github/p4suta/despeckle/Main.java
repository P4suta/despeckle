package io.github.p4suta.despeckle;

import io.github.p4suta.despeckle.cli.DespeckleCli;

/** Process entry point. */
public final class Main {

    private Main() {}

    /**
     * CLI entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        int exitCode = new DespeckleCli().run(args);
        System.exit(exitCode);
    }
}
