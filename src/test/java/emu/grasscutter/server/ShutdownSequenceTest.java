package emu.grasscutter.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ShutdownSequenceTest {
    @Test
    void failedPluginOrSessionCleanupStillRunsDatabaseDrainOnce() {
        var sequence = new ShutdownSequence();
        var drains = new AtomicInteger();
        Runnable failedPlugin = () -> { throw new IllegalStateException("plugin failure"); };
        Runnable failedSession = () -> { throw new IllegalStateException("session failure"); };
        assertThrows(IllegalStateException.class, () -> sequence.run(failedPlugin, failedSession, drains::incrementAndGet));
        assertEquals(1, drains.get(), "cleanup failure skipped the database drain");
        assertThrows(IllegalStateException.class, () -> sequence.run(drains::incrementAndGet));
        assertEquals(1, drains.get(), "a second shutdown retried completed cleanup");
    }

    @Test
    void interruptedStageCannotSkipLaterWriterDrain() {
        var sequence = new ShutdownSequence();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            sequence.run(() -> Thread.currentThread().interrupt(),
                    () -> assertFalse(Thread.currentThread().isInterrupted(), "writer drain must receive its timeout budget"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            pool.shutdownNow();
        }
    }

    @Test
    void interruptedCallerStillRunsCleanupAndRetainsInterrupt() {
        var sequence = new ShutdownSequence();
        var drains = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            sequence.run(drains::incrementAndGet);
            assertEquals(1, drains.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
