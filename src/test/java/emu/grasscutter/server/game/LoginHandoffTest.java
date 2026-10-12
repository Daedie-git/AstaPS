package emu.grasscutter.server.game;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoginHandoffTest {
    private static void resetBarrier() {
        LoginPersistenceHandoff.install(() -> CompletableFuture.failedFuture(
                new IllegalStateException("Persistence handoff not installed")));
    }
    @Test void reconnectWaitsForLoaderAndAcknowledgedWrites() {
        var writes = new CompletableFuture<Void>();
        var flushes = new AtomicInteger();
        LoginPersistenceHandoff.install(() -> { flushes.incrementAndGet(); return writes; });
        var old = new GameSession(null);
        var next = new GameSession(null);
        String account = "isolated-handoff-success";
        try {
            assertTrue(old.claimAccountLease(account));
            old.markLeaseLoaded();
            old.beginLoginWork();
            assertTrue(old.canSubmitSessionWrite());
            long generation = old.getAccountSessionGeneration();
            var retired = old.retireAccountLease();
            assertTrue(old.canSubmitSessionWrite(), "loaders and final logout producers retain write admission");
            assertFalse(old.acceptsGameWork());
            assertTrue(old.ownsAccountLease(), "queued writes remain current during retirement");
            assertEquals(0, flushes.get());
            assertFalse(next.claimAccountLease(account));
            old.endLoginWork();
            assertFalse(old.canSubmitSessionWrite(), "producer completion closes admission before flush");
            assertEquals(1, flushes.get());
            assertFalse(retired.isDone());
            assertFalse(next.claimAccountLease(account));
            writes.complete(null);
            retired.join();
            assertTrue(next.claimAccountLease(account));
            assertTrue(next.getAccountSessionGeneration() > generation);
            assertFalse(old.ownsAccountLease(), "old-generation writer execution must be fenced");
            assertFalse(old.canSubmitSessionWrite());
            old.releaseAccountLease();
            assertTrue(next.ownsAccountLease());
            assertEquals(1, flushes.get(), "retirement is once-only");
        } finally {
            AccountSessionLease.release(AccountSessionLease.current(account));
            resetBarrier();
        }
    }
    @Test void failedWritesKeepAccountExcluded() {
        LoginPersistenceHandoff.install(() -> CompletableFuture.failedFuture(new IllegalStateException("write failed")));
        var old = new GameSession(null);
        String account = "isolated-handoff-failure";
        try {
            assertTrue(old.claimAccountLease(account));
            old.markLeaseLoaded();
            assertThrows(CompletionException.class, () -> old.retireAccountLease().join());
            assertFalse(new GameSession(null).claimAccountLease(account));
            assertTrue(old.ownsAccountLease());
        } finally {
            AccountSessionLease.release(AccountSessionLease.current(account));
            resetBarrier();
        }
    }
}
