package emu.grasscutter.game.player;

import emu.grasscutter.game.dps.DPSAttackContext;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.AttackResultOuterClass.AttackResult;
import emu.grasscutter.server.game.GameSession;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

/** Logic-thread combat finalization; packet validation belongs outside this scope. */
public final class CombatDamageBatch implements AutoCloseable {
    private static final ThreadLocal<Map<GameSession, Batch>> ACTIVE =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private final GameSession session;
    private final Batch batch;
    private boolean complete;
    private boolean closed;

    private CombatDamageBatch(GameSession session, Batch batch) {
        this.session = session;
        this.batch = batch;
        batch.depth++;
    }

    public static CombatDamageBatch open(GameSession session) {
        return new CombatDamageBatch(session,
                ACTIVE.get().computeIfAbsent(session, ignored -> new Batch()));
    }

    /** Discard an existing batch when combat dispatch fails before entering its scope. */
    public static void invalidate(GameSession session) {
        var active = ACTIVE.get();
        var batch = active.get(session);
        if (batch != null) batch.failed = true;
        if (active.isEmpty()) ACTIVE.remove();
    }

    public static void enqueue(GameSession session, Scene scene, AttackResult attack) {
        var batch = ACTIVE.get().get(session);
        if (batch == null) throw new IllegalStateException("Combat enqueue outside packet scope");
        batch.attacks.addLast(new Pending(scene, attack));
    }

    /** Mark only successful processing; malformed siblings invalidate the whole queued batch. */
    public void complete() {
        complete = true;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (!complete) batch.failed = true;
        if (--batch.depth != 0) return;
        var active = ACTIVE.get();
        active.remove(session);
        if (active.isEmpty()) ACTIVE.remove();
        if (batch.failed) {
            batch.attacks.clear();
            return;
        }
        while (!batch.attacks.isEmpty()) {
            var pending = batch.attacks.removeFirst();
            DPSAttackContext.set(pending.attack());
            try {
                pending.scene().handleAttack(pending.attack());
            } finally {
                DPSAttackContext.clear();
            }
        }
    }

    private record Pending(Scene scene, AttackResult attack) {}
    private static final class Batch {
        int depth;
        boolean failed;
        final ArrayDeque<Pending> attacks = new ArrayDeque<>();
    }
}
