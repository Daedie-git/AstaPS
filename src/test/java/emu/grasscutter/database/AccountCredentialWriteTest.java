package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import dev.morphia.Datastore;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.Account;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

class AccountCredentialWriteTest {
    @Test
    void staleCredentialIssuerCannotReplaceNewBanOrPassword() throws Exception {
        var stored = new Document("_id", "test-account").append("persistenceVersion", 2L)
                .append("isBanned", true).append("password", "new-password").append("token", "new-token");
        try (var ignored = install(stored, false)) {
            var stale = new Account();
            stale.setId("test-account");
            assertThrows(RuntimeException.class, stale::generateLoginToken);
            assertEquals(true, stored.getBoolean("isBanned"));
            assertEquals("new-password", stored.getString("password"));
            assertEquals("new-token", stored.getString("token"));
        }
    }

    @Test void passwordResetVersionMissReportsAConflictRatherThanClaimingDeletion() throws Exception {
        var stored = new Document("_id", "test-account").append("persistenceVersion", 2L)
                .append("password", "new-password");
        try (var ignored = install(stored, false)) {
            var stale = new Account(); stale.setId("test-account");
            assertThrows(AccountWriteConflictException.class,
                    () -> AccountPasswordResetService.resetPassword(stale, "replacement-password"));
            assertEquals("new-password", stored.getString("password"));
        }
    }

    @Test
    void credentialWriteUpdatesOnlyCredentialAndVersionBeforeReturning() throws Exception {
        var stored = new Document("_id", "test-account").append("isBanned", true)
                .append("password", "password").append("token", "login-token");
        try (var ignored = install(stored, false)) {
            var account = new Account();
            account.setId("test-account");
            var key = account.generateV2SessionKey();
            assertEquals(key, stored.getString("sessionKey"));
            assertEquals("login-token", stored.getString("token"));
            assertEquals(true, stored.getBoolean("isBanned"));
            assertEquals("password", stored.getString("password"));
            assertEquals(1L, stored.getLong("persistenceVersion"));
        }
    }

    @Test
    void failedCredentialWriteLeavesLocalCredentialUnchanged() throws Exception {
        var stored = new Document("_id", "test-account");
        try (var ignored = install(stored, true)) {
            var account = new Account();
            account.setId("test-account");
            account.setToken("old-token");
            assertThrows(IllegalStateException.class, account::generateLoginToken);
            assertEquals("old-token", account.getToken());
        }
    }

    // An isolated Mongo operation recorder: no Mongo client, network or server is started.
    private static AutoCloseable install(Document stored, boolean fail) throws Exception {
        var collection = (MongoCollection<?>) Proxy.newProxyInstance(MongoCollection.class.getClassLoader(),
                new Class<?>[] {MongoCollection.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("updateOne")) throw new UnsupportedOperationException(method.getName());
                    if (fail) throw new IllegalStateException("injected write failure");
                    var filter = document((Bson) args[0]);
                    // Equality filters use an explicit $and; evaluate the actual generated filter.
                    boolean matches = matches(filter, stored);
                    if (matches) {
                        var update = document((Bson) args[1]);
                        stored.putAll(update.get("$set", Document.class));
                        var increments = update.get("$inc", Document.class);
                        if (increments != null) increments.forEach((key, value) -> stored.put(key,
                                ((Number) stored.getOrDefault(key, 0L)).longValue() + ((Number) value).longValue()));
                    }
                    return UpdateResult.acknowledged(matches ? 1 : 0, matches ? 1L : 0L, null);
                });
        var database = (MongoDatabase) Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(),
                new Class<?>[] {MongoDatabase.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getCollection")) return collection;
                    throw new UnsupportedOperationException(method.getName());
                });
        var datastore = (Datastore) Proxy.newProxyInstance(Datastore.class.getClassLoader(),
                new Class<?>[] {Datastore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getDatabase")) return database;
                    if (method.getName().equals("save")) {
                        if (fail) throw new IllegalStateException("injected write failure");
                        var account = (Account) args[0];
                        stored.put("token", account.getToken());
                        stored.put("sessionKey", account.getSessionKey());
                        stored.put("isBanned", false);
                        stored.put("password", account.getPassword());
                        return account;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var field = DatabaseManager.class.getDeclaredField("gameDatastore");
        var mode = Grasscutter.class.getDeclaredField("runModeOverride");
        field.setAccessible(true);
        mode.setAccessible(true);
        var failures = new IsolatedWriterFailures();
        var previous = field.get(null);
        var previousMode = mode.get(null);
        field.set(null, datastore);
        Grasscutter.setRunModeOverride(Grasscutter.ServerRunMode.HYBRID);
        return () -> { failures.close(); field.set(null, previous); mode.set(null, previousMode); };
    }

    private static Document document(Bson bson) {
        return Document.parse(bson.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toJson());
    }

    private static boolean matches(Document filter, Document stored) {
        if (filter.containsKey("$and")) return filter.getList("$and", Document.class).stream().allMatch(part -> matches(part, stored));
        return filter.entrySet().stream().allMatch(entry -> java.util.Objects.equals(entry.getValue(), stored.get(entry.getKey())));
    }
}
