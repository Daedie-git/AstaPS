package emu.grasscutter.game.inventory;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.props.PlayerProperty;

/** Convert newly increased Primogem balances, retaining change and failed inventory grants. */
public final class PrimogemAutoConvert {
    private PrimogemAutoConvert() {}

    private static final ThreadLocal<java.util.Map<Player, Pending>> DEFERRED =
            ThreadLocal.withInitial(java.util.IdentityHashMap::new);

    /** Reserve ordinary reward slots before conversion can insert fate items. */
    public static BatchConversion defer(Player player) {
        var pending = DEFERRED.get().computeIfAbsent(player, p -> new Pending(p.getPrimogems()));
        pending.depth++;
        return new BatchConversion(player, pending);
    }

    private static final class Pending {
        final int previous;
        int depth;
        boolean failed;
        Pending(int previous) { this.previous = previous; }
    }

    public static final class BatchConversion implements AutoCloseable {
        private final Player player;
        private final Pending pending;
        private boolean complete;
        private boolean closed;
        private BatchConversion(Player player, Pending pending) {
            this.player = player;
            this.pending = pending;
        }
        public void complete() { complete = true; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (!complete) pending.failed = true;
            if (--pending.depth != 0) return;
            var active = DEFERRED.get();
            active.remove(player);
            if (active.isEmpty()) DEFERRED.remove();
            if (!pending.failed) convertIncrease(player, pending.previous);
        }
    }

    public static boolean setPrimogems(Player player, int requested) {
        var inventory = player.getInventory();
        synchronized (inventory) {
            int previous = player.getPrimogems();
            if (!player.setProperty(PlayerProperty.PROP_PLAYER_HCOIN, requested)) return false;
            var active = DEFERRED.get();
            boolean deferred = active.containsKey(player);
            if (active.isEmpty()) DEFERRED.remove();
            if (!deferred) convertIncrease(player, previous);
            return true;
        }
    }

    private static void convertIncrease(Player player, int previous) {
        var inventory = player.getInventory();
        synchronized (inventory) {
            int total = player.getPrimogems();
            if (total <= previous || total < 160) return;
            int fates = total / 160;
            int remainder = total % 160;
            if (!inventory.canAddItemParamDatas(java.util.List.of(
                    new emu.grasscutter.data.common.ItemParamData(223, fates)))) return;
            // Spend before granting so plugins observe the converted balance. Refund a failed grant.
            if (!player.setProperty(PlayerProperty.PROP_PLAYER_HCOIN, remainder)) return;
            if (!inventory.addItem(223, fates, ActionReason.Shop)) {
                if (!player.setProperty(PlayerProperty.PROP_PLAYER_HCOIN, total))
                    throw new IllegalStateException("Could not refund failed Primogem conversion");
            }
        }
    }
}
