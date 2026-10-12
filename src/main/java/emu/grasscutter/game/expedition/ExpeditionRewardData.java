package emu.grasscutter.game.expedition;

import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.utils.Utils;
import lombok.Getter;

public class ExpeditionRewardData {
    @Getter private int itemId;
    @Getter private int minCount;
    @Getter private int maxCount;

    /** Server-side multiplier applied to every expedition claim. */
    private static final int REWARD_MULTIPLIER = 3;

    public GameItem getReward() {
        int count = Math.multiplyExact(Utils.randomRange(minCount, maxCount), REWARD_MULTIPLIER);
        return emu.grasscutter.game.inventory.ClaimRewardDelivery.declared(itemId, count);
    }
}
