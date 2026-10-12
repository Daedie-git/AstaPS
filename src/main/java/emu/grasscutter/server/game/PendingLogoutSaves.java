package emu.grasscutter.server.game;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Each account's last logout saves, so its next login can wait for them before loading. */
public final class PendingLogoutSaves {
    private static final Map<String, CompletableFuture<Void>> PENDING = new ConcurrentHashMap<>();

    public static void record(String accountId, CompletableFuture<Void> saves) {
        if (accountId == null || saves.isDone()) return;
        PENDING.put(accountId, saves);
        saves.whenComplete((ignored, failure) -> PENDING.remove(accountId, saves));
    }

    /** The account's unfinished logout saves, or null. */
    public static CompletableFuture<Void> get(String accountId) {
        return accountId == null ? null : PENDING.get(accountId);
    }

    private PendingLogoutSaves() {}
}
