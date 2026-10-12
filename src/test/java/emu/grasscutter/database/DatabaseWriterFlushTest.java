package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DatabaseWriterFlushTest {
    @Test void independentLogoutKeysUsePoolCapacityRatherThanAnotherKeysOrderedBudget() throws Exception {
        var pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(1), new ThreadPoolExecutor.CallerRunsPolicy());
        var manager = new DatabaseWriterManager(java.util.Map.of("default", pool), 1);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var saves = new java.util.concurrent.atomic.AtomicInteger();
        try {
            manager.submitOrdered(pool, "already-running-player", () -> { entered.countDown(); await(release); });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> manager.submitOrdered(pool, "logout-player", saves::incrementAndGet));
            assertDoesNotThrow(() -> manager.submitOrdered(pool, "logout-avatar", saves::incrementAndGet));
            release.countDown(); manager.flush().get(2, TimeUnit.SECONDS);
            assertEquals(2, saves.get());
        } finally { release.countDown(); manager.shutdown(Duration.ofSeconds(2)); }
    }
    @Test void waitingSameKeyWritesRemainBoundedAndCannotOvertakeTheirHead() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var manager = new DatabaseWriterManager(java.util.Map.of("default", pool), 1);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var order = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        try {
            manager.submitOrdered(pool, "same-player", () -> { entered.countDown(); await(release); order.add(1); });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> manager.submitOrdered(pool, "same-player", () -> order.add(2)));
            assertThrows(RejectedExecutionException.class, () -> manager.submitOrdered(pool, "same-player", () -> order.add(3)));
            release.countDown(); manager.flush().get(2, TimeUnit.SECONDS);
            assertEquals(java.util.List.of(1, 2), order);
        } finally { release.countDown(); manager.shutdown(Duration.ofSeconds(2)); }
    }

    @Test
    void handoffWaitsForEveryPoolWithoutRejectingNewWriters() throws Exception {
        var pools = new LinkedHashMap<String, ExecutorService>();
        for (var name : java.util.List.of("default", "account", "item", "group")) pools.put(name, Executors.newSingleThreadExecutor());
        var manager = new DatabaseWriterManager(pools);
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var releaseNewWrite = new CountDownLatch(1);
        try {
            pools.values().forEach(pool -> manager.submit(pool, () -> {
                entered.countDown();
                await(release);
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var flush = manager.flush();
            assertFalse(flush.isDone());
            assertDoesNotThrow(() -> manager.submit(pools.get("default"), () -> await(releaseNewWrite)));
            release.countDown();
            // New unrelated writes remain admitted, but cannot extend this pre-call handoff.
            flush.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            releaseNewWrite.countDown();
            assertTrue(manager.shutdown(Duration.ofSeconds(2)).completed());
        }
    }

    @Test
    void synchronousPreCallWriteIsIncluded() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var manager = new DatabaseWriterManager(java.util.Map.of("default", pool));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var write = callers.submit(() -> manager.runSynchronous(() -> { entered.countDown(); await(release); }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var flush = manager.flush();
            assertFalse(flush.isDone());
            release.countDown();
            flush.get(2, TimeUnit.SECONDS);
            write.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            manager.shutdown(Duration.ofSeconds(2));
        }
    }

    @Test
    void priorFailureRemainsUnacknowledged() {
        var pool = Executors.newSingleThreadExecutor();
        var manager = new DatabaseWriterManager(java.util.Map.of("default", pool));
        try {
            assertThrows(IllegalStateException.class, () -> manager.runSynchronous(() -> { throw new IllegalStateException("injected"); }));
            assertThrows(CompletionException.class, () -> manager.flush().join());
            assertThrows(CompletionException.class, () -> manager.flush().join());
        } finally {
            assertFalse(manager.shutdown(Duration.ofSeconds(2)).completed(), "failed writes must not become a successful shutdown");
        }
    }

    @Test
    void successfulRetryClearsOnlyItsOwnFailedWriteKey() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var manager = new DatabaseWriterManager(java.util.Map.of("default", pool));
        try {
            manager.submitOrdered(pool, "home-a", () -> { throw new IllegalStateException("injected home failure"); });
            pool.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThrows(CompletionException.class, () -> manager.flush().join());
            manager.submitOrdered(pool, "home-b", () -> {});
            pool.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThrows(CompletionException.class, () -> manager.flush().join(), "unrelated success cannot acknowledge lost data");
            manager.submitOrdered(pool, "home-a", () -> {});
            pool.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertDoesNotThrow(() -> manager.flush().join());
        } finally { manager.shutdown(Duration.ofSeconds(2)); }
    }

    @Test
    void failedCallerRunSaveDoesNotThrowIntoPacketHandler() throws Exception {
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        var manager = new DatabaseWriterManager(java.util.Map.of("default", pool));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try {
            manager.submit(pool, () -> { entered.countDown(); await(release); });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            manager.submit(pool, () -> {});
            assertDoesNotThrow(() -> manager.submit(pool, () -> { throw new IllegalStateException("transient write error"); }));
            assertThrows(CompletionException.class, () -> manager.flush().join());
        } finally { release.countDown(); manager.shutdown(Duration.ofSeconds(2)); }
    }

    @Test
    void interruptedCleanupStillDrainsAcceptedWrites() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var manager = new DatabaseWriterManager(java.util.Map.of("default", pool));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        manager.submit(pool, () -> { entered.countDown(); await(release); });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            new emu.grasscutter.server.ShutdownSequence().run(
                    () -> { Thread.currentThread().interrupt(); release.countDown(); },
                    () -> assertTrue(manager.shutdown(Duration.ofSeconds(2)).completed()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void accountVersionConflictDoesNotPoisonLaterHandoffs() {
        var pool = Executors.newSingleThreadExecutor();
        var manager = new DatabaseWriterManager(java.util.Map.of("default", pool));
        try {
            assertThrows(dev.morphia.VersionMismatchException.class, () -> manager.runSynchronous(() -> {
                throw new dev.morphia.VersionMismatchException(emu.grasscutter.game.Account.class, "test-account");
            }));
            assertDoesNotThrow(() -> manager.flush().join());
            assertTrue(manager.shutdown(Duration.ofSeconds(2)).completed());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
