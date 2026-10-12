package emu.grasscutter.database;

import com.mongodb.*;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.Account;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;

/** Real Morphia codec with an isolated Mongo replacement recorder; no client or network is opened. */
final class AccountMongoRecorder {
    static Datastore datastore(Document stored, Consumer<Document> beforeWrite) {
        var registry = new AtomicReference<CodecRegistry>(MongoClientSettings.getDefaultCodecRegistry());
        var collection = (MongoCollection<?>) Proxy.newProxyInstance(MongoCollection.class.getClassLoader(),
                new Class<?>[] {MongoCollection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "withCodecRegistry": registry.set((CodecRegistry) args[0]); return proxy;
                        case "withWriteConcern", "withReadConcern", "withReadPreference", "withDocumentClass": return proxy;
                        case "getCodecRegistry": return registry.get();
                        case "getWriteConcern": return WriteConcern.ACKNOWLEDGED;
                        case "getReadConcern": return ReadConcern.DEFAULT;
                        case "getReadPreference": return ReadPreference.primary();
                        case "getNamespace": return new MongoNamespace("audit_test", "accounts");
                        case "getDocumentClass": return Account.class;
                        case "replaceOne": {
                            var filter = Document.parse(((Bson) args[0]).toBsonDocument(Document.class, registry.get()).toJson());
                            var replacement = (Document) args[1];
                            boolean matched = matches(filter, stored);
                            if (matched) {
                                beforeWrite.accept(replacement);
                                stored.clear();
                                stored.putAll(replacement);
                            }
                            return UpdateResult.acknowledged(matched ? 1 : 0, matched ? 1L : 0L, null);
                        }
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        var database = (MongoDatabase) Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(),
                new Class<?>[] {MongoDatabase.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getCollection": return collection;
                        case "getCodecRegistry": return registry.get();
                        case "withCodecRegistry": registry.set((CodecRegistry) args[0]); return proxy;
                        case "withReadConcern", "withWriteConcern", "withReadPreference": return proxy;
                        case "getWriteConcern": return WriteConcern.ACKNOWLEDGED;
                        case "getReadConcern": return ReadConcern.DEFAULT;
                        case "getReadPreference": return ReadPreference.primary();
                        case "getName": return "audit_test";
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        var client = (MongoClient) Proxy.newProxyInstance(MongoClient.class.getClassLoader(),
                new Class<?>[] {MongoClient.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getDatabase")) return database;
                    throw new UnsupportedOperationException(method.getName());
                });
        var datastore = Morphia.createDatastore(client, "audit_test");
        datastore.getMapper().map(Account.class);
        return datastore;
    }

    static AutoCloseable install(Datastore datastore) throws Exception {
        var field = DatabaseManager.class.getDeclaredField("gameDatastore");
        var mode = Grasscutter.class.getDeclaredField("runModeOverride");
        field.setAccessible(true);
        mode.setAccessible(true);
        var previous = field.get(null);
        var previousMode = mode.get(null);
        var failures = new IsolatedWriterFailures();
        field.set(null, datastore);
        Grasscutter.setRunModeOverride(Grasscutter.ServerRunMode.HYBRID);
        return () -> { failures.close(); field.set(null, previous); mode.set(null, previousMode); };
    }

    private static boolean matches(Document filter, Document stored) {
        if (filter.containsKey("$and")) return filter.getList("$and", Document.class).stream().allMatch(part -> matches(part, stored));
        return filter.entrySet().stream().allMatch(entry -> {
            var expected = entry.getValue();
            if (expected instanceof Document condition && condition.containsKey("$eq")) expected = condition.get("$eq");
            var actual = stored.get(entry.getKey());
            if (expected instanceof Number first && actual instanceof Number second) return first.longValue() == second.longValue();
            return Objects.equals(expected, actual);
        });
    }
}
