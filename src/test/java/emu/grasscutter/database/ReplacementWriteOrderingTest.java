package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import dev.morphia.Datastore;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.world.SceneGroupInstance;
import org.bson.types.ObjectId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReplacementWriteOrderingTest {
    @Entity("audit_test_only")
    static class Snapshot {
        @Id int id = 17;
        int value;
        Snapshot(int value) { this.value = value; }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSecondSnapshotCannotOvertakeAnInFlightReplacement(boolean group) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var newerEntered = new CountDownLatch(1);
        var persisted = new AtomicInteger();
        var datastore = (Datastore) Proxy.newProxyInstance(Datastore.class.getClassLoader(),
                new Class<?>[] {Datastore.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("save")) throw new UnsupportedOperationException(method.getName());
                    var snapshot = args[0];
                    int serializedValue = snapshot instanceof Snapshot value ? value.value
                            : ((SceneGroupInstance) snapshot).getCachedVariables().get("audit-test");
                    if (serializedValue == 1) {
                        entered.countDown();
                        if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
                    } else newerEntered.countDown();
                    persisted.set(serializedValue);
                    return snapshot;
                });
        var field = DatabaseManager.class.getDeclaredField("gameDatastore");
        var mode = Grasscutter.class.getDeclaredField("runModeOverride");
        field.setAccessible(true);
        mode.setAccessible(true);
        var previous = field.get(null);
        var previousMode = mode.get(null);
        field.set(null, datastore);
        Grasscutter.setRunModeOverride(Grasscutter.ServerRunMode.HYBRID);
        var pool = (ThreadPoolExecutor) (group ? DatabaseHelper.getEventExecutorGroup() : DatabaseHelper.getEventExecutor());
        int previousCore = pool.getCorePoolSize();
        pool.setCorePoolSize(2);
        try {
            var id = new ObjectId();
            DatabaseHelper.saveGameAsync(snapshot(group, 1, id));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            DatabaseHelper.saveGameAsync(snapshot(group, 2, id));
            assertFalse(newerEntered.await(150, TimeUnit.MILLISECONDS), "new snapshot overtook in-flight write");
        } finally {
            release.countDown();
            try (var ignored = DatabaseWriteBarrier.acquire()) {
                assertEquals(2, persisted.get(), "old snapshot rolled persisted state back");
            } finally {
                pool.setCorePoolSize(previousCore);
                field.set(null, previous);
                mode.set(null, previousMode);
            }
        }
    }

    private static Object snapshot(boolean group, int value, ObjectId id) throws Exception {
        if (!group) return new Snapshot(value);
        var constructor = SceneGroupInstance.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var instance = constructor.newInstance();
        var idField = SceneGroupInstance.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(instance, id);
        instance.getCachedVariables().put("audit-test", value);
        return instance;
    }
}
