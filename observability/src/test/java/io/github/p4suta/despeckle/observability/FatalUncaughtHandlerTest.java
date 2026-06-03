package io.github.p4suta.despeckle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Covers both branches of {@link FatalUncaughtHandler#uncaughtException}. */
final class FatalUncaughtHandlerTest {

    @Test
    void outOfMemoryExitsWith137() {
        AtomicInteger code = new AtomicInteger(-1);
        FatalUncaughtHandler handler = new FatalUncaughtHandler(code::set);

        handler.uncaughtException(Thread.currentThread(), new OutOfMemoryError("heap"));

        assertEquals(FatalUncaughtHandler.OOM_EXIT_CODE, code.get());
        assertEquals(137, FatalUncaughtHandler.OOM_EXIT_CODE);
    }

    @Test
    void otherThrowablesAreLoggedButDoNotExit() {
        AtomicBoolean exited = new AtomicBoolean(false);
        FatalUncaughtHandler handler = new FatalUncaughtHandler(code -> exited.set(true));

        handler.uncaughtException(Thread.currentThread(), new RuntimeException("oops"));

        assertFalse(exited.get(), "a non-OOM throwable must not terminate the JVM");
    }

    @Test
    void defaultConstructorIsUsable() {
        // The no-arg ctor wires System::exit; just confirm it constructs (do not trigger OOM).
        assertNotNull(new FatalUncaughtHandler());
    }
}
