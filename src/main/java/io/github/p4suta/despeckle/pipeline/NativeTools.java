package io.github.p4suta.despeckle.pipeline;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Resolves and runs the external native tools the PDF pipeline shells out to ({@code pdfimages},
 * {@code pdfinfo}, {@code jbig2}, {@code qpdf}). Discovery mirrors {@code Leptonica} and the
 * report's {@code Flipbook}/{@code Webp}: an explicit {@code -Ddespeckle.<tool>.path} wins, else
 * the tool is looked up on {@code PATH}. A missing extraction/encode tool is fatal — the pipeline
 * cannot proceed — so {@link #resolve} throws; only the final {@code qpdf} linearize degrades
 * gracefully (its resolution is caught by the caller).
 */
final class NativeTools {

    private NativeTools() {}

    static String pdfimages() throws IOException {
        return resolve("pdfimages", "despeckle.pdfimages.path");
    }

    static String pdfinfo() throws IOException {
        return resolve("pdfinfo", "despeckle.pdfinfo.path");
    }

    static String jbig2() throws IOException {
        return resolve("jbig2", "despeckle.jbig2.path");
    }

    static String qpdf() throws IOException {
        return resolve("qpdf", "despeckle.qpdf.path");
    }

    /**
     * The tool path: {@code -D<property>} if set, else the first executable named {@code tool} on
     * PATH.
     */
    static String resolve(String tool, String property) throws IOException {
        String override = System.getProperty(property);
        if (override != null && !override.isBlank()) {
            return override;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator, -1)) {
                if (dir.isEmpty()) {
                    continue;
                }
                Path candidate = Path.of(dir, tool);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        throw new IOException(
                tool + " not found on PATH; install it or set -D" + property + "=/path/to/" + tool);
    }

    /** Run a command, discarding all output, failing on nonzero exit or timeout. */
    static void run(List<String> command, long timeoutSeconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        awaitExit(pb.start(), command, timeoutSeconds);
    }

    /**
     * Run a command and return its exit code, discarding output and failing only on timeout or a
     * launch failure — never on a nonzero exit. Lets a caller accept tool-specific "success with
     * warnings" codes (e.g. {@code qpdf}'s exit 3).
     */
    static int exitCode(List<String> command, long timeoutSeconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.get(0) + " timed out after " + timeoutSeconds + "s");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(command.get(0) + " was interrupted", e);
        }
        return process.exitValue();
    }

    /** Run a command and return its stdout bytes, failing on nonzero exit or timeout. */
    static byte[] capture(List<String> command, long timeoutSeconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        byte[] out;
        try (InputStream in = process.getInputStream()) {
            // The output we capture (a -list/-version report, or one page's JBIG2 stream) is small
            // and stderr is discarded, so reading stdout to EOF before waiting cannot deadlock.
            out = in.readAllBytes();
        }
        awaitExit(process, command, timeoutSeconds);
        return out;
    }

    private static void awaitExit(Process process, List<String> command, long timeoutSeconds)
            throws IOException {
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.get(0) + " timed out after " + timeoutSeconds + "s");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(command.get(0) + " was interrupted", e);
        }
        int code = process.exitValue();
        if (code != 0) {
            throw new IOException(command.get(0) + " failed with exit code " + code);
        }
    }

    /**
     * Run every task on {@code pool}, in submission order, surfacing the first failure as
     * IOException.
     */
    static <T> List<T> awaitAll(ExecutorService pool, List<Callable<T>> tasks) throws IOException {
        List<Future<T>> futures;
        try {
            futures = pool.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("pipeline interrupted", e);
        }
        List<T> results = new ArrayList<>(futures.size());
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("pipeline interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("pipeline task failed", cause);
            }
        }
        return results;
    }
}
