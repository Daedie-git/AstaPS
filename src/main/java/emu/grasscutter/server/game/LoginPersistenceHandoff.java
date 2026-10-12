package emu.grasscutter.server.game;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** Persistence installs a failure-propagating all-pool barrier before game admission starts. */
public final class LoginPersistenceHandoff {
    private static volatile Supplier<CompletableFuture<Void>> barrier = () ->
            CompletableFuture.failedFuture(new IllegalStateException("Persistence handoff not installed"));
    private LoginPersistenceHandoff() {}
    public static void install(Supplier<CompletableFuture<Void>> acknowledgedBarrier) {
        barrier = java.util.Objects.requireNonNull(acknowledgedBarrier);
    }
    public static CompletableFuture<Void> flush() {
        try { return barrier.get().orTimeout(30, TimeUnit.SECONDS); }
        catch (Throwable failure) { return CompletableFuture.failedFuture(failure); }
    }
}
