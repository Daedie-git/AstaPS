package emu.grasscutter.server.game;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.server.ShutdownSequence;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LoginProducerLifecycleTest {
    @Test void shutdownOrchestrationWaitsForPendingLoginBeforeWriterDrain() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var lifecycle = new LoginProducerLifecycle(executor);
        var closed = new CountDownLatch(1);
        var session = new GameSession(null) {
            @Override public void close() { super.close(); closed.countDown(); }
        };
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var drained = new AtomicBoolean(); var coordinator = Executors.newSingleThreadExecutor();
        try {
            assertTrue(lifecycle.submit(session, () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }, true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var stopping = coordinator.submit(() -> new ShutdownSequence().run(
                    () -> lifecycle.shutdown(Duration.ofSeconds(2)), () -> drained.set(true)));
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            // Join the actual pending-session work, not a mocked executor completion.
            assertFalse(session.loginWorkCompletion().isDone());
            assertFalse(drained.get());
            release.countDown(); stopping.get(5, TimeUnit.SECONDS);
            assertTrue(executor.isTerminated()); assertTrue(drained.get());
            assertFalse(lifecycle.submit(new GameSession(null), () -> fail("late producer"), true));
        } finally { release.countDown(); executor.shutdownNow(); coordinator.shutdownNow(); }
    }
    @Test void cancellationCompletesNeverStartedPendingSessions() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var lifecycle = new LoginProducerLifecycle(executor);
        var entered = new CountDownLatch(1);
        var first = new GameSession(null); var queued = new GameSession(null);
        try {
            lifecycle.submit(first, () -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { }
            }, true);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            lifecycle.submit(queued, () -> fail("cancelled login ran"), true);
            lifecycle.shutdown(Duration.ofMillis(30));
            assertTrue(queued.loginWorkCompletion().isDone());
            assertFalse(queued.acceptsGameWork()); assertTrue(executor.isTerminated());
        } finally { executor.shutdownNow(); }
    }
    @Test void componentIgnoringCancellationFailsRealShutdownStageAfterFollowupJoin() throws Exception {
        var component = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var drainAttempted = new AtomicBoolean();
        try {
            component.execute(() -> {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> new ShutdownSequence().run(
                    () -> LoginProducerLifecycle.stopExecutor(component, Duration.ofMillis(30)),
                    () -> drainAttempted.set(true)));
            assertTrue(drainAttempted.get(), "accepted writes still receive best-effort drain on failure");
            assertFalse(component.isTerminated());
        } finally { release.countDown(); component.shutdownNow(); assertTrue(component.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
