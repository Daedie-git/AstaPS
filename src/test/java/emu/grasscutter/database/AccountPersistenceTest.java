package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.game.Account;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/** Account replacements must acknowledge failures and complete before returning. */
public class AccountPersistenceTest {
    @Test
    public void replacementWaitsForDatabaseWrite() throws Exception {
        var entered = new CountDownLatch(1);
        var complete = new CountDownLatch(1);
        var stored = new Document("_id", "account-1");
        var datastore = AccountMongoRecorder.datastore(stored, document -> {
            entered.countDown();
            try {
                if (!complete.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        });
        try (var ignored = AccountMongoRecorder.install(datastore)) {
            var issued = CompletableFuture.supplyAsync(() -> {
                var account = new Account();
                account.setId("account-1");
                account.setToken("test-token");
                account.save();
                return account.getToken();
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> issued.get(100, TimeUnit.MILLISECONDS));
            } finally { complete.countDown(); }
            assertEquals(issued.get(5, TimeUnit.SECONDS), stored.getString("token"));
        }
    }

    @Test
    public void aFailedReplacementIsPropagated() throws Exception {
        var stored = new Document("_id", "account-2");
        var datastore = AccountMongoRecorder.datastore(stored, document -> { throw new IllegalStateException("injected write failure"); });
        try (var ignored = AccountMongoRecorder.install(datastore)) {
            var account = new Account();
            account.setId("account-2");
            assertThrows(IllegalStateException.class, account::save);
            assertNull(account.getPersistenceVersion());
            assertFalse(stored.containsKey("persistenceVersion"));
        }
    }
}
