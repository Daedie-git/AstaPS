package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LoginLoadGroupTest {
    @Test void waitsForEveryRequiredComponent() {
        var executor = Executors.newFixedThreadPool(6);
        try {
            var loaded = new AtomicBoolean[7];
            var jobs = new Runnable[7];
            for (int i = 0; i < jobs.length; i++) {
                loaded[i] = new AtomicBoolean();
                var flag = loaded[i];
                jobs[i] = () -> flag.set(true);
            }
            LoginLoadGroup.load(executor, Duration.ofSeconds(1), jobs);
            for (var flag : loaded) assertTrue(flag.get());
        } finally { executor.shutdownNow(); }
    }
    @Test void failuresCancelAndJoinSiblingsWithoutExhaustingPool() {
        var executor = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 10; i++) {
                var started = new CountDownLatch(1);
                var stopped = new AtomicBoolean();
                assertThrows(IllegalStateException.class, () -> LoginLoadGroup.load(
                        executor, Duration.ofSeconds(1),
                        () -> { try { started.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                            throw new IllegalStateException("avatars failed"); },
                        () -> { started.countDown();
                            try { new CountDownLatch(1).await(); }
                            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                            finally { stopped.set(true); } }));
                assertTrue(stopped.get(), "worker must stop before failed login returns");
            }
            var healthy = new AtomicBoolean();
            LoginLoadGroup.load(executor, Duration.ofSeconds(1), () -> healthy.set(true));
            assertTrue(healthy.get());
        } finally { executor.shutdownNow(); }
    }
    @Test void nonCooperativeLoaderFailsExplicitlyAtDeadline() {
        var executor = Executors.newSingleThreadExecutor();
        var release = new CountDownLatch(1);
        try {
            assertThrows(LoginLoadGroup.UnstoppedLoaderException.class, () -> LoginLoadGroup.load(
                    executor, Duration.ofMillis(100), () -> {
                        while (release.getCount() != 0) {
                            try { release.await(); } catch (InterruptedException ignored) { }
                        }
                    }));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
    @Test void dependencyWaitPropagatesInterruption() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> emu.grasscutter.utils.Utils.waitFor(() -> false));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test void avatarFailureNeverStartsDependentInventory() {
        var executor = Executors.newFixedThreadPool(6);
        try {
            var inventoryStarted = new AtomicBoolean();
            assertThrows(IllegalStateException.class, () -> LoginLoadGroup.load(
                    executor, Duration.ofSeconds(1), () -> {
                        Runnable avatars = () -> { throw new IllegalStateException("avatars failed"); };
                        avatars.run();
                        inventoryStarted.set(true);
                    }));
            assertFalse(inventoryStarted.get());
        } finally { executor.shutdownNow(); }
    }
    @Test void allComponentsShareOneTotalDeadline() {
        var executor = Executors.newFixedThreadPool(6);
        Runnable blocked = () -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        };
        try {
            long start = System.nanoTime();
            assertThrows(IllegalStateException.class, () -> LoginLoadGroup.load(
                    executor, Duration.ofMillis(100), blocked, blocked, blocked));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
        } finally { executor.shutdownNow(); }
    }
    @Test void interruptedCallerPreservesInterrupt() {
        var executor = Executors.newSingleThreadExecutor();
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, () -> LoginLoadGroup.load(
                    executor, Duration.ofSeconds(1), () -> {}));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); executor.shutdownNow(); }
    }
}
