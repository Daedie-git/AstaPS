package emu.grasscutter.server.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Timer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GameLoopLifecycleTest {
    @Test
    void stopCancelsTickProducerAndRunsCleanupOnce() throws Exception {
        var lifecycle = new GameLoopLifecycle();
        var ticked = new CountDownLatch(1);
        var ticks = new AtomicInteger();
        var cleanups = new AtomicInteger();
        try {
            lifecycle.start(() -> { ticks.incrementAndGet(); ticked.countDown(); }, 10);
            assertTrue(ticked.await(2, TimeUnit.SECONDS));
            lifecycle.stop(cleanups::incrementAndGet);
            lifecycle.stop(cleanups::incrementAndGet);
            assertEquals(1, cleanups.get(), "restart and exit hook repeated cleanup");
            int atStop = ticks.get();
            Thread.sleep(100);
            assertEquals(atStop, ticks.get(), "ticks kept producing writes after shutdown");
        } finally { forceCancel(lifecycle); }
    }

    @Test
    void stopWaitsForInFlightTickBeforeCleanup() throws Exception {
        var lifecycle = new GameLoopLifecycle();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cleaned = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            lifecycle.start(() -> { entered.countDown(); await(release); }, 10);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var stop = callers.submit(() -> lifecycle.stop(cleaned::countDown));
            assertFalse(cleaned.await(100, TimeUnit.MILLISECONDS), "cleanup raced the active tick");
            release.countDown();
            stop.get(2, TimeUnit.SECONDS);
            assertEquals(0, cleaned.getCount());
        } finally { release.countDown(); forceCancel(lifecycle); }
    }

    @Test
    void concurrentStopCallersWaitForTheSameCleanup() throws Exception {
        var lifecycle = new GameLoopLifecycle();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cleanups = new AtomicInteger();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Runnable cleanup = () -> { cleanups.incrementAndGet(); entered.countDown(); await(release); };
            var first = callers.submit(() -> lifecycle.stop(cleanup));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = callers.submit(() -> lifecycle.stop(cleanup));
            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertEquals(1, cleanups.get());
        } finally { release.countDown(); forceCancel(lifecycle); }
    }

    @Test
    void hungTickReportsFailureWithoutWaitingForever() throws Exception {
        var lifecycle = new GameLoopLifecycle();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            lifecycle.start(() -> { entered.countDown(); await(release); }, 10);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var cleanupAttempts = new AtomicInteger();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(6), () ->
                    assertThrows(IllegalStateException.class, () -> lifecycle.stop(cleanupAttempts::incrementAndGet)));
            assertEquals(1, cleanupAttempts.get(), "tick timeout must not skip logout, world saves and transport cleanup");
        } finally { release.countDown(); forceCancel(lifecycle); }
    }

    private static void forceCancel(GameLoopLifecycle lifecycle) throws Exception {
        var field = GameLoopLifecycle.class.getDeclaredField("timer");
        field.setAccessible(true);
        var timer = (Timer) field.get(lifecycle);
        if (timer != null) timer.cancel();
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
