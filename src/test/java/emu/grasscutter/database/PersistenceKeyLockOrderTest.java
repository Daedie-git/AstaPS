package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PersistenceKeyLockOrderTest {
    @org.junit.jupiter.api.Test
    void firstIdentityReservationDoesNotAcquireTheEntityMonitor() throws Exception {
        var entity = new PersistenceKeyTest.ObjectDocument();
        var pool = Executors.newSingleThreadExecutor();
        Future<Object> reader = null;
        try {
            synchronized (entity) {
                reader = pool.submit(() -> PersistenceKey.of(entity));
                var task = reader;
                assertNotNull(assertDoesNotThrow(() -> task.get(1, TimeUnit.SECONDS)));
            }
            assertEquals(reader.get(), PersistenceKey.of(entity));
        } finally {
            if (reader != null) reader.get(3, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"player", "long", "object", "string"})
    void assignedIdentityDoesNotAcquireAnEntityMonitorBelowTheSessionFence(String type) throws Exception {
        Object entity = switch (type) {
            case "player" -> {
                var player = new emu.grasscutter.game.player.Player();
                player.setUid(42);
                yield player;
            }
            case "long" -> new PersistenceKeyTest.LongDocument();
            case "object" -> {
                var document = new PersistenceKeyTest.ObjectDocument();
                document.id = new ObjectId();
                yield document;
            }
            default -> {
                var home = emu.grasscutter.game.home.GameHome.create(42);
                home.setId("existing-natural-id");
                yield home;
            }
        };
        var expected = PersistenceKey.of(entity);
        var pool = Executors.newSingleThreadExecutor();
        Future<Object> reader = null;
        try {
            synchronized (entity) {
                reader = pool.submit(() -> PersistenceKey.of(entity));
                var task = reader;
                assertEquals(expected, assertDoesNotThrow(() -> task.get(1, TimeUnit.SECONDS)),
                        "Known IDs must not wait on the entity while a session fence is held");
            }
        } finally {
            // A red test releases the entity monitor before joining its blocked reader.
            if (reader != null) reader.get(3, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
