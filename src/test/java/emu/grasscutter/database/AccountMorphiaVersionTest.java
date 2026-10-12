package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import dev.morphia.VersionMismatchException;
import emu.grasscutter.game.Account;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class AccountMorphiaVersionTest {
    @Test
    void legacyReplacementAdoptsVersionAndStaleSnapshotCannotEraseBan() throws Exception {
        var stored = new Document("_id", "account-test").append("banReason", "old reason");
        try (var ignored = AccountMongoRecorder.install(AccountMongoRecorder.datastore(stored, document -> {}))) {
            var first = account();
            first.setBanned(true);
            var stale = account();
            DatabaseHelper.saveAccount(first);
            assertEquals(1L, first.getPersistenceVersion());
            assertEquals(1L, stored.getLong("persistenceVersion"));
            assertEquals(true, stored.getBoolean("isBanned"));
            assertFalse(stored.containsKey("banReason"), "explicit null must clear an existing field");
            assertThrows(VersionMismatchException.class, () -> DatabaseHelper.saveAccount(stale));
            assertEquals(true, stored.getBoolean("isBanned"));
            assertNull(stale.getPersistenceVersion(), "failed replacement must not advance local version");
        }
    }

    private static Account account() {
        var account = new Account();
        account.setId("account-test");
        account.setUsername("test");
        return account;
    }
}
