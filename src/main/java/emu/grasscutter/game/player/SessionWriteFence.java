package emu.grasscutter.game.player;

import emu.grasscutter.game.achievement.Achievements;
import emu.grasscutter.game.activity.PlayerActivityData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.dailytask.DailyTaskManager;
import emu.grasscutter.game.friends.Friendship;
import emu.grasscutter.game.home.GameHome;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.server.game.AccountSessionLease;
import emu.grasscutter.server.game.GameSession;
import java.lang.ref.*;
import java.util.concurrent.*;

/** Identity-bound child generations never resolve ownership by a newer player's UID. */
public final class SessionWriteFence {
    private record Stamp(String accountId, long generation) {}
    private static final ReferenceQueue<Object> COLLECTED = new ReferenceQueue<>();
    private static final ConcurrentMap<Key, Stamp> OWNERS = new ConcurrentHashMap<>();
    private static final class Key extends WeakReference<Object> {
        final int hash;
        Key(Object object) { super(object, COLLECTED); hash = System.identityHashCode(object); }
        public int hashCode() { return hash; }
        public boolean equals(Object other) {
            return this == other || other instanceof Key key && get() != null && get() == key.get();
        }
    }
    private SessionWriteFence() {}

    public static <T> T bind(T object, Player player) {
        if (object == null || player == null || player.getSession() == null) return object;
        var session = player.getSession();
        var accountId = session.getLeaseAccountId();
        reap();
        OWNERS.putIfAbsent(new Key(object), new Stamp(accountId, session.getAccountSessionGeneration()));
        return object;
    }
    private static void reap() {
        Reference<?> key;
        while ((key = COLLECTED.poll()) != null) OWNERS.remove(key);
    }
    private static Stamp stamp(Object object) {
        Player player = null;
        if (object instanceof Player root) player = root;
        else if (object instanceof Avatar avatar) player = avatar.getPlayer();
        else if (object instanceof GameMainQuest quest) player = quest.getOwner();
        else if (object instanceof BasePlayerDataManager manager) player = manager.getPlayer();
        else if (object instanceof DailyTaskManager manager) player = manager.getPlayer();
        else if (object instanceof Friendship friendship) player = friendship.getOwner();
        else if (object instanceof Achievements achievements) player = achievements.getPlayer();
        else if (object instanceof PlayerActivityData activity) player = activity.getPlayer();
        bind(object, player);
        reap();
        return OWNERS.get(new Key(object));
    }
    private static GameSession current(Stamp stamp) {
        if (stamp.accountId() == null) return null;
        var lease = AccountSessionLease.current(stamp.accountId());
        return lease != null && lease.generation() == stamp.generation()
                && lease.owner() instanceof GameSession session ? session : null;
    }
    /** Serialize queue admission with the same session monitor used by retirement. */
    public static void submit(Object object, Runnable write) {
        // HomeWorld retains one shared GameHome object across owner reconnects, not a
        // separately loaded per-session snapshot. Its runtime owner is deliberately mutable.
        GameSession session;
        if (object instanceof GameHome home && home.getPlayer() != null) {
            session = home.getPlayer().getSession();
            if (session == null) { write.run(); return; }
        } else {
            var stamp = stamp(object);
            if (stamp == null) { write.run(); return; }
            session = current(stamp);
        }
        if (session == null) return;
        synchronized (session) {
            if (session.canSubmitSessionWrite()) write.run();
        }
    }

    public static boolean maySubmit(Object object) {
        if (object instanceof GameHome home && home.getPlayer() != null)
            return home.getPlayer().canSubmitSessionWrite();
        var stamp = stamp(object);
        if (stamp == null) return true; // Unbound offline maintenance is not a live-session write.
        var session = current(stamp);
        return session != null && session.canSubmitSessionWrite();
    }
    public static boolean mayExecute(Object object) {
        if (object instanceof GameHome home && home.getPlayer() != null)
            return home.getPlayer().isSessionGenerationCurrent();
        var stamp = stamp(object);
        return stamp == null || current(stamp) != null;
    }
}
