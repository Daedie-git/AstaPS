package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import emu.grasscutter.game.friends.Friendship;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.server.game.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ReverseFriendFenceTest {
    static Player player(GameSession session, int uid) {
        var p = new Player() {
            @Override public java.util.Optional<emu.grasscutter.game.home.GameHome> tryGetHome() {
                return java.util.Optional.empty();
            }
            private emu.grasscutter.game.friends.PlayerProfile isolatedProfile;
            @Override public emu.grasscutter.game.friends.PlayerProfile getProfile() {
                if (isolatedProfile == null) isolatedProfile = new emu.grasscutter.game.friends.PlayerProfile(this) {
                    @Override public Player getPlayer() { return null; }
                };
                return isolatedProfile;
            }
        };
        p.setUid(uid); p.setMainCharacterId(10000007); p.setSession(session);
        return p;
    }
    @Test void retainedManagerCannotChangeReverseProfileOrDeleteAfterReconnect() throws Exception { run(false, false); }
    @Test void managerCannotQueueReverseWriteAfterRetirementDuringLookup() throws Exception { run(true, false); }
    @Test void managerCannotQueueReverseDeleteAfterRetirementDuringLookup() throws Exception { run(true, true); }
    private void run(boolean duringLookup, boolean delete) throws Exception {
        var old = new GameSession(null); var next = new GameSession(null);
        String account = "reverse-friend-" + duringLookup + "-" + delete;
        var origin = player(old, 909001); var peer = player(null, 909002);
        var local = new Friendship(origin, peer, origin); local.setIsFriend(true);
        var reverse = new Friendship(peer, origin, origin); reverse.setIsFriend(true); reverse.setOwner(null);
        var initialProfile = reverse.getFriendProfile();
        var writes = new AtomicInteger(); var deletes = new AtomicInteger();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        Field field = DatabaseManager.class.getDeclaredField("gameDatastore"); field.setAccessible(true);
        Object previous = field.get(null);
        Object query = Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[]{Query.class}, (proxy, method, args) -> {
            if (method.getName().equals("filter")) return proxy;
            if (duringLookup) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
            if (method.getName().equals("stream")) return Stream.of(reverse);
            if (method.getName().equals("first")) return reverse;
            throw new AssertionError(method.getName());
        });
        field.set(null, Proxy.newProxyInstance(Datastore.class.getClassLoader(), new Class<?>[]{Datastore.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "find" -> query;
                case "save" -> { writes.incrementAndGet(); yield args[0]; }
                case "delete" -> { if (args[0] == reverse) deletes.incrementAndGet(); yield com.mongodb.client.result.DeleteResult.acknowledged(1); }
                default -> throw new AssertionError("Unexpected database call: " + method.getName());
            };
        }));
        try {
            GameServer.prepareLoginAdmission(); assertTrue(old.claimAccountLease(account)); old.markLeaseLoaded();
            origin.getFriendsList().addFriend(local);
            Future<?> lookup = null;
            if (duringLookup) {
                lookup = executor.submit(() -> {
                    if (delete) origin.getFriendsList().deleteFriend(peer.getUid());
                    else origin.getFriendsList().save();
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            }
            old.retireAccountLease().get(5, TimeUnit.SECONDS);
            assertTrue(next.claimAccountLease(account));
            if (duringLookup) { release.countDown(); lookup.get(5, TimeUnit.SECONDS); }
            else { origin.getFriendsList().save(); origin.getFriendsList().deleteFriend(peer.getUid()); }
            DatabaseHelper.flushForLoginHandoff().get(5, TimeUnit.SECONDS);
            assertEquals(0, writes.get()); assertEquals(0, deletes.get());
            if (!duringLookup) assertSame(initialProfile, reverse.getFriendProfile());
            // A genuine offline manager still performs its maintenance mutation.
            release.countDown(); peer.getFriendsList().save();
            DatabaseHelper.flushForLoginHandoff().get(5, TimeUnit.SECONDS);
            assertEquals(1, writes.get());
        } finally {
            release.countDown(); executor.shutdownNow();
            try (var ignored = DatabaseWriteBarrier.acquire()) { field.set(null, previous); }
            AccountSessionLease.release(AccountSessionLease.current(account));
        }
    }
}
