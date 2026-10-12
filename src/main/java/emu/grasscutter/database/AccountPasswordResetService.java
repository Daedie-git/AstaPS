package emu.grasscutter.database;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.unset;

import emu.grasscutter.game.Account;
import java.util.Objects;
import org.bson.conversions.Bson;

/** Changes a password and revokes both token types in one synchronous MongoDB update. */
public final class AccountPasswordResetService {
    private AccountPasswordResetService() {}

    static Bson passwordResetUpdate(String passwordHash) {
        Objects.requireNonNull(passwordHash, "passwordHash");
        return combine(
                set("password", passwordHash),
                unset("token"),
                unset("sessionKey"),
                com.mongodb.client.model.Updates.inc("persistenceVersion", 1L));
    }

    public static void resetPassword(Account account, String passwordHash) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(account.getId(), "account.id");
        Bson update = passwordResetUpdate(passwordHash);

        // Async account writes must finish before the password and token update. A single
        // MongoDB update makes the new password and the two token revocations atomic.
        synchronized (account) {
        Long version = account.getPersistenceVersion();
        try (DatabaseWriteBarrier ignored = DatabaseWriteBarrier.acquire()) {
            var accounts = DatabaseManager.getAccountDatastore().getDatabase().getCollection("accounts");
            var result = accounts.updateOne(
                    com.mongodb.client.model.Filters.and(eq("_id", account.getId()), eq("persistenceVersion", version)), update);
            if (result.getMatchedCount() != 1) {
                throw new AccountWriteConflictException();
            }
        }

        // Keep the caller's in-memory account consistent with the persisted credentials.
        account.setPassword(passwordHash);
        account.setToken(null);
        account.setSessionKey(null);
        account.setPersistenceVersion(version == null ? 1L : Math.addExact(version, 1L));
        }
    }
}
