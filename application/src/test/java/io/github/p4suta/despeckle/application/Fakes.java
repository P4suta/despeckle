package io.github.p4suta.despeckle.application;

import io.github.p4suta.despeckle.domain.model.BatchBook;
import io.github.p4suta.despeckle.domain.model.OutputFormat;
import io.github.p4suta.despeckle.domain.model.ProcessOptions;
import io.github.p4suta.despeckle.domain.model.ProcessResult;
import io.github.p4suta.despeckle.port.BatchReporter;
import io.github.p4suta.despeckle.port.Jbig2Assembler;
import io.github.p4suta.despeckle.port.PageCleaner;
import io.github.p4suta.despeckle.port.PdfImageExtractor;
import io.github.p4suta.despeckle.port.PdfLinearizer;
import io.github.p4suta.despeckle.port.Reporter;
import io.github.p4suta.despeckle.port.ReporterFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Hand-written fake adapters for the {@code port} interfaces — no Mockito (it is not on the
 * classpath). Each fake produces just enough on-disk output for the next orchestration stage to
 * find, and records what it was asked to do, so the application services can be unit-tested in
 * isolation from {@code :infrastructure}.
 */
final class Fakes {

    private Fakes() {}

    /** A {@link PageCleaner} that writes a stub output file and returns a fixed result. */
    static final class FakePageCleaner implements PageCleaner {
        final AtomicInteger calls = new AtomicInteger();
        private final ProcessResult result;

        FakePageCleaner(ProcessResult result) {
            this.result = result;
        }

        @Override
        public ProcessResult clean(
                Path input, Path output, OutputFormat format, ProcessOptions options) {
            try {
                Files.writeString(output, "cleaned", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            calls.incrementAndGet();
            return result;
        }
    }

    /** A {@link Reporter} that counts {@code addPage}/{@code finish} calls. */
    static final class RecordingReporter implements Reporter {
        final AtomicInteger pages = new AtomicInteger();
        final AtomicInteger finished = new AtomicInteger();

        @Override
        public void addPage(
                Path relativeStem, Path inputImage, Path outputImage, ProcessResult result) {
            pages.incrementAndGet();
        }

        @Override
        public void finish() {
            finished.incrementAndGet();
        }
    }

    /** A {@link ReporterFactory} that hands out a single {@link RecordingReporter}. */
    static final class RecordingReporterFactory implements ReporterFactory {
        final RecordingReporter reporter = new RecordingReporter();
        @Nullable Path createdFor;

        @Override
        public Reporter create(Path reportDir, boolean flipbook) {
            createdFor = reportDir;
            return reporter;
        }

        @Override
        public Reporter noOp() {
            return Reporter.noOp();
        }
    }

    /** A {@link PdfImageExtractor} that emits {@code pages} stub {@code *.tif} files. */
    static final class FakePdfImageExtractor implements PdfImageExtractor {
        private final int pages;
        private final int dpi;
        private final @Nullable String failOn;
        boolean dominantDpiCalled;

        FakePdfImageExtractor(int pages, int dpi) {
            this(pages, dpi, null);
        }

        FakePdfImageExtractor(int pages, int dpi, @Nullable String failOn) {
            this.pages = pages;
            this.dpi = dpi;
            this.failOn = failOn;
        }

        @Override
        public int dominantDpi(Path pdf) {
            dominantDpiCalled = true;
            return dpi;
        }

        @Override
        public void extract(Path pdf, Path outDir, int jobs, ExecutorService pool)
                throws IOException {
            if (failOn != null && pdf.getFileName().toString().contains(failOn)) {
                throw new IOException("fake extract failed for " + pdf);
            }
            for (int i = 1; i <= pages; i++) {
                Files.writeString(
                        outDir.resolve("page-%02d.tif".formatted(i)),
                        "tiff",
                        StandardCharsets.UTF_8);
            }
        }
    }

    /** A {@link Jbig2Assembler} that writes a stub PDF and records the call. */
    static final class FakeJbig2Assembler implements Jbig2Assembler {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void assemble(
                Path imageDir,
                Path outPdf,
                @Nullable Path source,
                OptionalInt forcedDpi,
                ExecutorService pool,
                Path scratchDir)
                throws IOException {
            Files.writeString(outPdf, "%PDF-fake", StandardCharsets.UTF_8);
            calls.incrementAndGet();
        }
    }

    /** A {@link PdfLinearizer} that records that it ran. */
    static final class FakePdfLinearizer implements PdfLinearizer {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void linearize(Path pdf) {
            calls.incrementAndGet();
        }
    }

    /** A {@link BatchReporter} that captures the books it was given. */
    static final class RecordingBatchReporter implements BatchReporter {
        @Nullable List<BatchBook> books;

        @Override
        public void write(Path reportParent, List<BatchBook> books) {
            this.books = books;
        }
    }
}
