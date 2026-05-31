package io.github.p4suta.despeckle;

import io.github.p4suta.despeckle.cli.DespeckleCommand;
import picocli.CommandLine;

/** Process entry point. */
public final class Main {

    private Main() {}

    /**
     * CLI entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new DespeckleCommand()).execute(args);
        System.exit(exitCode);
    }
}
