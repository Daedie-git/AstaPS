package emu.grasscutter.server.game;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Each account's last logout saves, so its next login can wait for them before loading. */
public final class PendingLogoutSaves {
    private static final Map<String, CompletableFuture<Void>> PENDING = new ConcurrentHashMap<>();

    /** Adds saves the account's next login must wait for, on top of any still pending. */
    public static void record(String accountId, CompletableFuture<Void> saves) {
        if (accountId == null || saves.isDone()) return;
        var pending = PENDING.compute(
                accountId,
                (ignored, earlier) -> earlier == null || earlier.isDone() ? saves : CompletableFuture.allOf(earlier, saves));
        pending.whenComplete((ignored, failure) -> PENDING.remove(accountId, pending));
    }

    /** The account's unfinished logout saves, or null. */
    public static CompletableFuture<Void> get(String accountId) {
        return accountId == null ? null : PENDING.get(accountId);
    }

    private PendingLogoutSaves() {}
}
