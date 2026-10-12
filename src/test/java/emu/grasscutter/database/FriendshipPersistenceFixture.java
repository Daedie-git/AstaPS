package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import dev.morphia.Datastore;
import emu.grasscutter.game.friends.Friendship;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Records actual friendship writer submissions without connecting to MongoDB. */
public final class FriendshipPersistenceFixture implements AutoCloseable {
    private final Field field;
    private final Object previous;
    private final Map<Friendship, Integer> saves = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Friendship, Integer> deletes = Collections.synchronizedMap(new IdentityHashMap<>());

    public FriendshipPersistenceFixture() throws Exception {
        field = DatabaseManager.class.getDeclaredField("gameDatastore");
        field.setAccessible(true);
        try (var ignored = DatabaseWriteBarrier.acquire()) {
            previous = field.get(null);
            field.set(null, Proxy.newProxyInstance(Datastore.class.getClassLoader(), new Class<?>[]{Datastore.class},
                    (proxy, method, args) -> {
                        assertTrue(method.getName().equals("save") || method.getName().equals("delete"),
                                "Unexpected database method: " + method.getName());
                        var friendship = assertInstanceOf(Friendship.class, args[0]);
                        if (method.getName().equals("save")) {
                            saves.merge(friendship, 1, Integer::sum);
                            return friendship;
                        }
                        deletes.merge(friendship, 1, Integer::sum);
                        return com.mongodb.client.result.DeleteResult.acknowledged(1);
                    }));
        }
    }

    public int saves(Friendship friendship) { return saves.getOrDefault(friendship, 0); }
    public int deletes(Friendship friendship) { return deletes.getOrDefault(friendship, 0); }
    public void awaitWrites() throws Exception {
        DatabaseHelper.flushForLoginHandoff().get(5, TimeUnit.SECONDS);
    }
    @Override public void close() throws Exception {
        try (var ignored = DatabaseWriteBarrier.acquire()) { field.set(null, previous); }
    }
}
