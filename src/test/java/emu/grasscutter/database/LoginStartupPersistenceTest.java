package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;
import dev.morphia.Datastore;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.battlepass.BattlePassManager;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.server.game.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LoginStartupPersistenceTest {
    private static final class Store implements AutoCloseable {
        final Map<Object, Integer> writes = Collections.synchronizedMap(new IdentityHashMap<>());
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final Field field;
        final Object previous;
        final IsolatedWriterFailures failures = new IsolatedWriterFailures();
        volatile Object blocked;
        volatile boolean fail;
        Store() throws Exception {
            field = DatabaseManager.class.getDeclaredField("gameDatastore");
            field.setAccessible(true);
            previous = field.get(null);
            field.set(null, Proxy.newProxyInstance(Datastore.class.getClassLoader(), new Class<?>[] {Datastore.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("save") && !method.getName().equals("delete"))
                            throw new AssertionError("Unexpected live database interface: " + method.getName());
                        var object = args[0];
                        if (object == blocked) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        if (fail) throw new IllegalStateException("isolated write failure");
                        writes.merge(object, 1, Integer::sum);
                        return method.getName().equals("delete") ? com.mongodb.client.result.DeleteResult.acknowledged(1) : object;
                    }));
        }
        int count(Object object) { return writes.getOrDefault(object, 0); }
        public void close() throws Exception {
            release.countDown();
            try (var ignored = DatabaseWriteBarrier.acquire()) {
                field.set(null, previous);
            } finally { failures.close(); }
        }
    }
    private static Player player(GameSession session, int uid) {
        var player = new Player();
        player.setUid(uid);
        player.setMainCharacterId(10000007);
        player.setSession(session);
        return player;
    }
    private static GameItem item(Player player) {
        var item = new GameItem();
        item.setCount(1);
        item.setOwner(player);
        return item;
    }
    @Test void productionStartupInstallsAcknowledgedBarrierAndFencesLateChildren() throws Exception {
        String account = "isolated-production-startup";
        var old = new GameSession(null);
        var next = new GameSession(null);
        try (var store = new Store()) {
            GameServer.prepareLoginAdmission();
            assertTrue(old.claimAccountLease(account));
            old.markLeaseLoaded();
            var player = player(old, 902001);
            var item = item(player);
            var avatar = new Avatar();
            avatar.setOwner(player);
            var bp = new BattlePassManager(player);
            var quest = new GameMainQuest();
            var uid = GameMainQuest.class.getDeclaredField("ownerUid");
            uid.setAccessible(true);
            uid.setInt(quest, player.getUid());
            quest.setOwner(player);
            store.blocked = item;
            item.save();
            avatar.save();
            bp.save();
            quest.save();
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            var handoff = old.retireAccountLease();
            assertFalse(handoff.isDone());
            assertFalse(next.claimAccountLease(account));
            item.save();
            DatabaseHelper.deleteItem(item);
            avatar.save();
            bp.save();
            quest.save();
            quest.delete();
            // Maintenance/unrelated writes remain admitted while the handoff is pending.
            var maintenance = new Avatar();
            maintenance.save();
            store.release.countDown();
            handoff.get(5, TimeUnit.SECONDS);
            DatabaseHelper.flushForLoginHandoff().get(5, TimeUnit.SECONDS);
            assertEquals(1, store.count(item));
            assertEquals(1, store.count(avatar));
            assertEquals(1, store.count(bp));
            assertEquals(1, store.count(quest));
            assertEquals(1, store.count(maintenance));
            assertTrue(next.claimAccountLease(account));
            item.save();
            avatar.save();
            bp.save();
            quest.save();
            var nextItem = item(player(next, 902001));
            nextItem.save();
            DatabaseHelper.flushForLoginHandoff().get(5, TimeUnit.SECONDS);
            assertEquals(1, store.count(item));
            assertEquals(1, store.count(avatar));
            assertEquals(1, store.count(bp));
            assertEquals(1, store.count(quest));
            assertEquals(1, store.count(nextItem));
        } finally { AccountSessionLease.release(AccountSessionLease.current(account)); }
    }
    @Test void retainedHomeUsesItsExplicitNewOwnerWithoutAdmittingOldHomeSnapshots() throws Exception {
        String account = "isolated-shared-home-reconnect";
        var old = new GameSession(null);
        var next = new GameSession(null);
        try (var store = new Store()) {
            GameServer.prepareLoginAdmission();
            assertTrue(old.claimAccountLease(account));
            old.markLeaseLoaded();
            var oldPlayer = player(old, 902005);
            var home = emu.grasscutter.game.home.GameHome.of()
                    .id("shared-home").ownerUid(oldPlayer.getUid()).player(oldPlayer).build();
            var oldSnapshot = emu.grasscutter.game.home.GameHome.of()
                    .id("shared-home").ownerUid(oldPlayer.getUid()).player(oldPlayer).build();
            home.save();
            old.retireAccountLease().get(5, TimeUnit.SECONDS);
            home.save();
            assertTrue(next.claimAccountLease(account));
            home.setPlayer(player(next, 902005));
            home.save();
            oldSnapshot.save();
            DatabaseHelper.flushForLoginHandoff().get(5, TimeUnit.SECONDS);
            assertEquals(2, store.count(home));
            assertEquals(0, store.count(oldSnapshot));
        } finally { AccountSessionLease.release(AccountSessionLease.current(account)); }
    }

    @Test void retirementCannotPassAnAdmittedProducerBeforeItsWriteIsQueued() throws Exception {
        String account = "isolated-atomic-child-admission";
        var old = new GameSession(null);
        var workers = Executors.newFixedThreadPool(2);
        var admitted = new CountDownLatch(1);
        var queueWrite = new CountDownLatch(1);
        try (var store = new Store()) {
            GameServer.prepareLoginAdmission();
            assertTrue(old.claimAccountLease(account));
            old.markLeaseLoaded();
            var item = item(player(old, 902004));
            store.blocked = item;
            var producer = workers.submit(() -> emu.grasscutter.game.player.SessionWriteFence.submit(item, () -> {
                admitted.countDown();
                try { assertTrue(queueWrite.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new RuntimeException(failure); }
                item.save();
            }));
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            var retirementStarted = new CountDownLatch(1);
            var retirement = CompletableFuture.supplyAsync(() -> {
                retirementStarted.countDown();
                return old.retireAccountLease();
            }, workers).thenCompose(future -> future);
            assertTrue(retirementStarted.await(5, TimeUnit.SECONDS));
            assertFalse(retirement.isDone());
            queueWrite.countDown();
            producer.get(5, TimeUnit.SECONDS);
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            assertFalse(retirement.isDone(), "admitted write must be covered by the handoff snapshot");
            store.release.countDown();
            retirement.get(5, TimeUnit.SECONDS);
            assertEquals(1, store.count(item));
            assertTrue(new GameSession(null).claimAccountLease(account));
        } finally {
            queueWrite.countDown();
            workers.shutdownNow();
            AccountSessionLease.release(AccountSessionLease.current(account));
        }
    }
    @Test void installedProviderFailureQuarantinesReconnect() throws Exception {
        String account = "isolated-startup-provider-failure";
        var old = new GameSession(null);
        try (var store = new Store()) {
            GameServer.prepareLoginAdmission();
            assertTrue(old.claimAccountLease(account));
            old.markLeaseLoaded();
            store.fail = true;
            item(player(old, 902002)).save();
            assertThrows(ExecutionException.class, () -> old.retireAccountLease().get(5, TimeUnit.SECONDS));
            assertFalse(new GameSession(null).claimAccountLease(account));
        } finally { AccountSessionLease.release(AccountSessionLease.current(account)); }
    }
    @Test void actualQueuedItemExecutionRejectsSupersededGeneration() throws Exception {
        String account = "isolated-stale-queued-child";
        var old = new GameSession(null);
        var release = new CountDownLatch(1);
        try (var store = new Store()) {
            GameServer.prepareLoginAdmission();
            assertTrue(old.claimAccountLease(account));
            var item = item(player(old, 902003));
            var id = item.ensurePersistenceId();
            var entered = new CountDownLatch(1);
            DatabaseHelper.getWriterManager().submitOrdered(DatabaseHelper.getEventExecutorItem(), id, () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new RuntimeException(failure); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            item.save();
            // Inject a stale continuation without trusting normal handoff ordering.
            AccountSessionLease.release(AccountSessionLease.current(account));
            assertTrue(new GameSession(null).claimAccountLease(account));
            release.countDown();
            DatabaseHelper.flushForLoginHandoff().get(5, TimeUnit.SECONDS);
            assertEquals(0, store.count(item));
        } finally {
            release.countDown();
            AccountSessionLease.release(AccountSessionLease.current(account));
        }
    }
}
