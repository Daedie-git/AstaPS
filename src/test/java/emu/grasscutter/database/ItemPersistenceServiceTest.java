package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.game.inventory.GameItem;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class ItemPersistenceServiceTest {
    @Test
    void serializedPositiveSaveMustCommitBeforeDeletion() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var manager = new DatabaseWriterManager(Map.of("item", pool));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var deleted = new CountDownLatch(1);
        var records = new ConcurrentHashMap<ObjectId, Integer>();
        var item = item(3);
        var service = new ItemPersistenceService(manager, pool, value -> {
            int serialized = value.getCount();
            entered.countDown();
            await(release);
            records.put(value.ensurePersistenceId(), serialized);
        }, value -> { records.remove(value.ensurePersistenceId()); deleted.countDown(); });
        try {
            service.save(item);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            item.setCount(0);
            service.delete(item);
            assertFalse(deleted.await(100, TimeUnit.MILLISECONDS), "delete overtook serialized save");
            release.countDown();
            manager.flush().get(2, TimeUnit.SECONDS);
            assertTrue(records.isEmpty(), "consumed item resurrected on reload");
        } finally {
            release.countDown();
            assertTrue(manager.shutdown(Duration.ofSeconds(2)).completed());
        }
    }

    @Test
    void consumeBeforeInitialInsertionHasStableIdAndNoSurvivingRecord() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var manager = new DatabaseWriterManager(Map.of("item", pool));
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var records = new ConcurrentHashMap<ObjectId, Integer>();
        manager.submit(pool, () -> { entered.countDown(); await(release); });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var item = item(1);
        var service = new ItemPersistenceService(manager, pool,
                value -> records.put(value.ensurePersistenceId(), value.getCount()),
                value -> records.remove(value.ensurePersistenceId()));
        try {
            service.save(item);
            var id = item.ensurePersistenceId();
            assertNotNull(id);
            item.setCount(0);
            service.delete(item);
            assertSame(id, item.ensurePersistenceId());
            release.countDown();
            manager.flush().get(2, TimeUnit.SECONDS);
            assertTrue(records.isEmpty());
        } finally {
            release.countDown();
            assertTrue(manager.shutdown(Duration.ofSeconds(2)).completed());
        }
    }

    private static GameItem item(int count) throws Exception {
        var item = new GameItem();
        item.setCount(count);
        var owner = GameItem.class.getDeclaredField("ownerId");
        owner.setAccessible(true);
        owner.setInt(item, 23);
        return item;
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
