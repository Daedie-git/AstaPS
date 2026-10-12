package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import dev.morphia.Datastore;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.activity.PlayerActivityData;
import emu.grasscutter.game.home.GameHome;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PersistenceKeyTest {
    @Entity("audit_ids")
    static class ObjectDocument {
        @Id ObjectId id;
    }

    @Entity("audit_ids")
    static class IntegerDocument {
        @Id int id = 17;
    }

    @Entity("audit_long_ids")
    static class LongDocument {
        @Id long id = 18L;
    }

    @Entity("audit_unsupported_ids")
    static class UnsupportedDocument {
        @Id UUID id;
    }

    private static Object stringDocument(boolean home) {
        return home ? GameHome.create(42)
                : PlayerActivityData.of().uid(42).activityId(99).watcherInfoMap(new HashMap<>()).build();
    }

    private static String stringId(Object document) {
        return document instanceof GameHome home ? home.getId() : ((PlayerActivityData) document).getId();
    }

    private static void stringId(Object document, String id) {
        if (document instanceof GameHome home) home.setId(id);
        else ((PlayerActivityData) document).setId(id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void newHomeAndActivityGetStableStringIdsBeforeQueueing(boolean home) {
        Object document = stringDocument(home);
        assertNull(stringId(document));
        Object key = PersistenceKey.of(document);
        String id = stringId(document);
        assertNotNull(id);
        assertTrue(ObjectId.isValid(id));
        assertEquals(key, PersistenceKey.of(document));
        assertEquals(id, stringId(document));
        assertNotEquals(key, PersistenceKey.of(stringDocument(home)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void existingStringIdsAreNeverReplaced(boolean home) {
        Object first = stringDocument(home);
        Object second = stringDocument(home);
        stringId(first, "existing-natural-id");
        stringId(second, "existing-natural-id");
        assertEquals(PersistenceKey.of(first), PersistenceKey.of(second));
        assertEquals("existing-natural-id", stringId(first));
    }

    @Test
    void concurrentReservationsForOneHomeReturnOneIdentity() throws Exception {
        var home = GameHome.create(42);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Future<Object>>();
            for (int i = 0; i < 32; i++) tasks.add(pool.submit(() -> PersistenceKey.of(home)));
            var keys = new HashSet<Object>();
            for (var task : tasks) keys.add(task.get(3, TimeUnit.SECONDS));
            assertEquals(1, keys.size());
            assertTrue(ObjectId.isValid(home.getId()));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void objectIdsAreStableAndAssignedNumericIdsKeepTheirTypes() {
        var document = new ObjectDocument();
        Object key = PersistenceKey.of(document);
        assertNotNull(document.id);
        ObjectId original = document.id;
        assertEquals(key, PersistenceKey.of(document));
        assertSame(original, document.id);
        var another = new ObjectDocument();
        another.id = original;
        assertEquals(key, PersistenceKey.of(another));
        var integer = new IntegerDocument();
        var number = new LongDocument();
        assertEquals(PersistenceKey.of(integer), PersistenceKey.of(new IntegerDocument()));
        assertEquals(PersistenceKey.of(number), PersistenceKey.of(new LongDocument()));
        assertEquals(17, integer.id);
        assertEquals(18L, number.id);
    }

    @Test
    void unsupportedMissingIdsAreNotFabricated() {
        var document = new UnsupportedDocument();
        assertThrows(IllegalArgumentException.class, () -> PersistenceKey.of(document));
        assertNull(document.id);
    }

    @Test
    void allTopLevelPersistedEntitiesUseCoveredIdTypes() {
        int inspected = 0;
        for (Class<?> type : Grasscutter.reflector.getTypesAnnotatedWith(Entity.class)) {
            if (type.getEnclosingClass() != null || !type.getName().startsWith("emu.grasscutter.")) continue;
            Field id = null;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Id.class)) id = field;
                }
            }
            if (id == null) continue; // Embedded entity models are not standalone save targets.
            assertTrue(Set.of(String.class, ObjectId.class, int.class, long.class).contains(id.getType()),
                    "Uncovered ID type in " + type.getName());
            inspected++;
        }
        assertTrue(inspected >= 15, "Entity inventory did not cover the game persistence models");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void normalHomeAndActivitySaveReachTheWriterWithAnAssignedId(boolean home) throws Exception {
        Object document = stringDocument(home);
        if (document instanceof GameHome value) {
            // Shared-home fencing needs an owner; offline ownership leaves session fences intact.
            value.setPlayer(new emu.grasscutter.game.player.Player());
        }
        var observed = new AtomicReference<String>();
        var datastore = (Datastore) Proxy.newProxyInstance(Datastore.class.getClassLoader(),
                new Class<?>[] {Datastore.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("save")) throw new UnsupportedOperationException(method.getName());
                    observed.set(stringId(args[0]));
                    return args[0];
                });
        Field field = DatabaseManager.class.getDeclaredField("gameDatastore");
        field.setAccessible(true);
        Object original = field.get(null);
        field.set(null, datastore);
        try {
            if (document instanceof GameHome value) value.save();
            else ((PlayerActivityData) document).save();
            DatabaseHelper.flushForLoginHandoff().get(3, TimeUnit.SECONDS);
            assertNotNull(observed.get());
            assertEquals(stringId(document), observed.get());
        } finally {
            try (var ignored = DatabaseWriteBarrier.acquire()) {
                field.set(null, original);
            }
        }
    }
}
