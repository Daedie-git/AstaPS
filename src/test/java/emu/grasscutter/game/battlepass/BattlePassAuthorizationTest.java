package emu.grasscutter.game.battlepass;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemUseData;
import emu.grasscutter.data.excels.BattlePassRewardData;
import emu.grasscutter.data.excels.RewardData;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

class BattlePassAuthorizationTest {
    @Test
    void unrelatedGlobalRewardIsNotBattlePassEligibility() {
        RewardData old = GameData.getRewardDataMap().put(991900, new RewardData());
        try {
            assertFalse(BattlePassCompatHelper.isRewardAllowed(new BattlePassManager(), 1, 991900));
        } finally {
            if (old == null) GameData.getRewardDataMap().remove(991900);
            else GameData.getRewardDataMap().put(991900, old);
        }
    }

    @Test
    void unselectedPlanCannotAuthorizeReward() {
        var row =
                new Gson()
                        .fromJson(
                                "{\"indexId\":4,\"level\":1,\"freeRewardIdList\":[991901]}",
                                BattlePassRewardData.class);
        var old = GameData.getBattlePassRewardDataMap().put(401, row);
        try {
            assertFalse(BattlePassCompatHelper.isRewardAllowed(new BattlePassManager(), 1, 991901));
        } finally {
            if (old == null) GameData.getBattlePassRewardDataMap().remove(401);
            else GameData.getBattlePassRewardDataMap().put(401, old);
        }
    }

    @Test
    void choiceCannotExtendIntoAdjacentGlobalRewards() throws Exception {
        var use =
                new Gson()
                        .fromJson(
                                "{\"useOp\":\"ITEM_USE_GRANT_SELECT_REWARD\",\"useParam\":[\"991910\"]}",
                                ItemUseData.class);
        var old = GameData.getRewardDataMap().put(991911, new RewardData());
        Method resolve =
                BattlePassSelectChestHelper.class.getDeclaredMethod(
                        "resolveChoiceRewardOrItem", int.class, ItemUseData.class, int.class);
        resolve.setAccessible(true);
        try {
            assertEquals(991910, resolve.invoke(null, 999, use, 1));
            assertEquals(-1, resolve.invoke(null, 999, use, 2));
        } finally {
            if (old == null) GameData.getRewardDataMap().remove(991911);
            else GameData.getRewardDataMap().put(991911, old);
        }
    }
}
