package io.github.p4suta.despeckle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.p4suta.despeckle.application.Fakes.FakeJbig2Assembler;
import io.github.p4suta.despeckle.application.Fakes.FakePageCleaner;
import io.github.p4suta.despeckle.application.Fakes.FakePdfImageExtractor;
import io.github.p4suta.despeckle.application.Fakes.FakePdfLinearizer;
import io.github.p4suta.despeckle.application.Fakes.RecordingReporterFactory;
import io.github.p4suta.despeckle.domain.model.ProcessOptions;
import io.github.p4suta.despeckle.domain.model.ProcessResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the whole PDF→PDF pipeline through fake ports (covers it and the despeckle service). */
final class PdfPipelineServiceTest {

    private static final ProcessResult LIGHT = new ProcessResult(50, 45, 500, 495);

    private static PdfPipelineService pipeline(FakePdfImageExtractor extractor) {
        DespeckleService despeckle =
                new DespeckleService(new FakePageCleaner(LIGHT), new RecordingReporterFactory());
        return new PdfPipelineService(
                extractor, despeckle, new FakeJbig2Assembler(), new FakePdfLinearizer());
    }

    private static PdfPipelineService.Config config(Path in, Path out, boolean force) {
        return new PdfPipelineService.Config(
                in, out, ProcessOptions.defaults(), 1, force, null, false);
    }

    @Test
    void extractsCleansAssemblesAndLinearizes(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("book.pdf");
        Path out = tmp.resolve("book-clean.pdf");
        Files.writeString(in, "%PDF-source", StandardCharsets.UTF_8);
        FakePdfImageExtractor extractor = new FakePdfImageExtractor(3, 300);
        FakeJbig2Assembler assembler = new FakeJbig2Assembler();
        FakePdfLinearizer linearizer = new FakePdfLinearizer();
        DespeckleService despeckle =
                new DespeckleService(new FakePageCleaner(LIGHT), new RecordingReporterFactory());

        DespeckleService.Summary summary =
                new PdfPipelineService(extractor, despeckle, assembler, linearizer)
                        .run(config(in, out, false));

        assertEquals(3, summary.pages(), "the extractor's 3 pages are all cleaned");
        assertTrue(extractor.dominantDpiCalled, "DPI is read from the scan when not forced");
        assertEquals(1, assembler.calls.get());
        assertEquals(1, linearizer.calls.get());
        assertTrue(Files.exists(out), "the output PDF is written");
        assertTrue(noPipelineTempLeft(tmp), "the work directory is cleaned up");
    }

    @Test
    void missingInputPdfFails(@TempDir Path tmp) {
        PdfPipelineService service = pipeline(new FakePdfImageExtractor(1, 300));
        assertThrows(
                IOException.class,
                () -> service.run(config(tmp.resolve("nope.pdf"), tmp.resolve("out.pdf"), false)));
    }

    @Test
    void existingOutputWithoutForceFails(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("book.pdf");
        Path out = tmp.resolve("book-clean.pdf");
        Files.writeString(in, "%PDF", StandardCharsets.UTF_8);
        Files.writeString(out, "old", StandardCharsets.UTF_8);

        PdfPipelineService service = pipeline(new FakePdfImageExtractor(1, 300));
        assertThrows(IOException.class, () -> service.run(config(in, out, false)));
    }

    private static boolean noPipelineTempLeft(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.noneMatch(
                    p -> p.getFileName().toString().startsWith(".despeckle-pipeline-"));
        }
    }
}
