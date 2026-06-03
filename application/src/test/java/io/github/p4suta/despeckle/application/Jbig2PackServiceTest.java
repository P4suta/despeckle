package io.github.p4suta.despeckle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.p4suta.despeckle.application.Fakes.FakeJbig2Assembler;
import io.github.p4suta.despeckle.application.Fakes.FakePdfLinearizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit-tests the {@code topdf} back end against fake assembler/linearizer ports. */
final class Jbig2PackServiceTest {

    private static Jbig2PackService.Config config(Path imageDir, Path out, boolean force) {
        return new Jbig2PackService.Config(imageDir, out, null, OptionalInt.of(300), 1, force);
    }

    @Test
    void packsTheImageDirectoryAndLinearizes(@TempDir Path tmp) throws IOException {
        Path images = Files.createDirectories(tmp.resolve("cleaned"));
        Files.writeString(images.resolve("page-01.pbm"), "x", StandardCharsets.UTF_8);
        Path out = tmp.resolve("book.pdf");
        FakeJbig2Assembler assembler = new FakeJbig2Assembler();
        FakePdfLinearizer linearizer = new FakePdfLinearizer();

        new Jbig2PackService(assembler, linearizer).run(config(images, out, false));

        assertEquals(1, assembler.calls.get());
        assertEquals(1, linearizer.calls.get());
        assertTrue(Files.exists(out), "the JBIG2 PDF is written");
    }

    @Test
    void missingImageDirFails(@TempDir Path tmp) {
        Jbig2PackService service =
                new Jbig2PackService(new FakeJbig2Assembler(), new FakePdfLinearizer());
        assertThrows(
                IOException.class,
                () -> service.run(config(tmp.resolve("nope"), tmp.resolve("out.pdf"), false)));
    }

    @Test
    void existingOutputWithoutForceFails(@TempDir Path tmp) throws IOException {
        Path images = Files.createDirectories(tmp.resolve("cleaned"));
        Path out = tmp.resolve("book.pdf");
        Files.writeString(out, "old", StandardCharsets.UTF_8);

        Jbig2PackService service =
                new Jbig2PackService(new FakeJbig2Assembler(), new FakePdfLinearizer());
        assertThrows(IOException.class, () -> service.run(config(images, out, false)));
    }
}
