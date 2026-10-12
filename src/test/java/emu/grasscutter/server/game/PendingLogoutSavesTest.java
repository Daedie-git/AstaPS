package emu.grasscutter.server.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PendingLogoutSavesTest {
    @Test
    void entryLastsUntilItsOwnSavesFinish() {
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        PendingLogoutSaves.record("pending-test", first);
        PendingLogoutSaves.record("pending-test", second);

        first.complete(null);
        assertFalse(PendingLogoutSaves.get("pending-test").isDone(), "an older logout must not clear a newer one");

        second.complete(null);
        assertNull(PendingLogoutSaves.get("pending-test"));
    }

    @Test
    void finishedSavesAreNotRecorded() {
        PendingLogoutSaves.record("done-test", CompletableFuture.completedFuture(null));
        assertNull(PendingLogoutSaves.get("done-test"));
    }
}
