package io.github.p4suta.despeckle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.p4suta.despeckle.application.Fakes.FakeJbig2Assembler;
import io.github.p4suta.despeckle.application.Fakes.FakePageCleaner;
import io.github.p4suta.despeckle.application.Fakes.FakePdfImageExtractor;
import io.github.p4suta.despeckle.application.Fakes.FakePdfLinearizer;
import io.github.p4suta.despeckle.application.Fakes.RecordingBatchReporter;
import io.github.p4suta.despeckle.application.Fakes.RecordingReporterFactory;
import io.github.p4suta.despeckle.domain.model.BookStatus;
import io.github.p4suta.despeckle.domain.model.ProcessOptions;
import io.github.p4suta.despeckle.domain.model.ProcessResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit-tests the batch driver: continue-on-error, skip-existing, and the batch-index write. */
final class PdfBatchServiceTest {

    private static final ProcessResult LIGHT = new ProcessResult(50, 45, 500, 495);

    private PdfBatchService batch(
            RecordingBatchReporter reporter, FakePdfImageExtractor extractor) {
        DespeckleService despeckle =
                new DespeckleService(new FakePageCleaner(LIGHT), new RecordingReporterFactory());
        PdfPipelineService pipeline =
                new PdfPipelineService(
                        extractor, despeckle, new FakeJbig2Assembler(), new FakePdfLinearizer());
        return new PdfBatchService(pipeline, reporter);
    }

    private static PdfBatchService.Config config(
            Path in,
            Path out,
            boolean force,
            @org.jspecify.annotations.Nullable Path reportParent) {
        return new PdfBatchService.Config(
                in, out, ProcessOptions.defaults(), 1, force, "", reportParent, false);
    }

    @Test
    void cleansGoodBooksAndContinuesPastAFailure(@TempDir Path tmp) throws IOException {
        Path in = Files.createDirectories(tmp.resolve("scans"));
        for (String name : List.of("a.pdf", "b.pdf", "bad.pdf")) {
            Files.writeString(in.resolve(name), "%PDF", StandardCharsets.UTF_8);
        }
        Path reports = tmp.resolve("reports");
        RecordingBatchReporter reporter = new RecordingBatchReporter();
        // The extractor throws for any book whose name contains "bad".
        PdfBatchService service = batch(reporter, new FakePdfImageExtractor(2, 300, "bad"));

        PdfBatchService.Summary summary =
                service.run(config(in, tmp.resolve("out"), true, reports));

        assertEquals(2, summary.ok());
        assertEquals(1, summary.failed());
        assertEquals(0, summary.skipped());
        assertEquals(4, summary.totalPages(), "2 good books × 2 pages");
        var books = reporter.books;
        assertNotNull(books, "the batch index is written when a report dir is set");
        assertEquals(3, books.size());
        assertEquals(1, books.stream().filter(b -> b.status() == BookStatus.FAILED).count());
    }

    @Test
    void skipsBooksWhoseOutputExistsWithoutForce(@TempDir Path tmp) throws IOException {
        Path in = Files.createDirectories(tmp.resolve("scans"));
        Files.writeString(in.resolve("a.pdf"), "%PDF", StandardCharsets.UTF_8);
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve("a.pdf"), "already-done", StandardCharsets.UTF_8);

        PdfBatchService.Summary summary =
                batch(new RecordingBatchReporter(), new FakePdfImageExtractor(2, 300))
                        .run(config(in, out, false, null));

        assertEquals(0, summary.ok());
        assertEquals(1, summary.skipped());
    }

    @Test
    void emptyDirectoryIsAZeroSummary(@TempDir Path tmp) throws IOException {
        Path in = Files.createDirectories(tmp.resolve("scans"));

        PdfBatchService.Summary summary =
                batch(new RecordingBatchReporter(), new FakePdfImageExtractor(2, 300))
                        .run(config(in, tmp.resolve("out"), true, null));

        assertEquals(0, summary.ok());
        assertEquals(0, summary.failed());
    }
}
