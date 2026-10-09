package emu.grasscutter.game.inventory;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.props.PlayerProperty;

/** Convert newly increased Primogem balances, retaining change and failed inventory grants. */
public final class PrimogemAutoConvert {
    private PrimogemAutoConvert() {}

    public static boolean setPrimogems(Player player, int requested) {
        var inventory = player.getInventory();
        synchronized (inventory) {
            int previous = player.getPrimogems();
            if (!player.setProperty(PlayerProperty.PROP_PLAYER_HCOIN, requested)) return false;
            int total = player.getPrimogems();
            if (total <= previous || total < 160) return true;
            int fates = total / 160;
            int remainder = total % 160;
            // Spend before granting so plugins observe the converted balance. Refund a failed grant.
            if (!player.setProperty(PlayerProperty.PROP_PLAYER_HCOIN, remainder)) return true;
            if (!inventory.addItem(223, fates, ActionReason.Shop)) {
                if (!player.setProperty(PlayerProperty.PROP_PLAYER_HCOIN, total))
                    throw new IllegalStateException("Could not refund failed Primogem conversion");
            }
            return true;
        }
    }
}
