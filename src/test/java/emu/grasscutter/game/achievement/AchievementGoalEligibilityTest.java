package emu.grasscutter.game.achievement;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.achievement.*;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.net.packet.BasePacket;

import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.*;

class AchievementGoalEligibilityTest {
    @Test
    void incompleteCollectionCannotBeClaimed() throws Exception {
        var u = Unsafe.class.getDeclaredField("theUnsafe");
        u.setAccessible(true);
        var p = (FakePlayer) ((Unsafe) u.get(null)).allocateInstance(FakePlayer.class);
        p.inv = (FakeInventory) ((Unsafe) u.get(null)).allocateInstance(FakeInventory.class);
        var a = new FakeAchievements(p);
        var gson = new Gson();
        var oldGoal =
                GameData.getAchievementGoalDataMap()
                        .put(
                                991300,
                                gson.fromJson(
                                        "{\"id\":991300,\"finishRewardId\":991301}",
                                        AchievementGoalData.class));
        var oldAchievement =
                GameData.getAchievementDataMap()
                        .put(
                                991302,
                                gson.fromJson(
                                        "{\"id\":991302,\"goalId\":991300,\"progress\":1}",
                                        AchievementData.class));
        var oldItem =
                GameData.getItemDataMap()
                        .put(
                                201,
                                gson.fromJson(
                                        "{\"id\":201,\"itemType\":\"ITEM_VIRTUAL\"}",
                                        ItemData.class));
        var reward = new RewardData();
        reward.rewardId = 991301;
        reward.rewardItemList = List.of(new ItemParamData(201, 5));
        var oldReward = GameData.getRewardDataMap().put(991301, reward);
        try {
            a.takeGoalReward(List.of(991300));
            assertEquals(0, p.inv.grants);
            assertTrue(a.getTakenGoalRewardIdList().isEmpty());
        } finally {
            if (oldGoal == null) GameData.getAchievementGoalDataMap().remove(991300);
            else GameData.getAchievementGoalDataMap().put(991300, oldGoal);
            if (oldAchievement == null) GameData.getAchievementDataMap().remove(991302);
            else GameData.getAchievementDataMap().put(991302, oldAchievement);
            if (oldItem == null) GameData.getItemDataMap().remove(201);
            else GameData.getItemDataMap().put(201, oldItem);
            if (oldReward == null) GameData.getRewardDataMap().remove(991301);
            else GameData.getRewardDataMap().put(991301, oldReward);
        }
    }

    static class FakeAchievements extends Achievements {
        FakeAchievements(Player p) {
            super(null, 0, p, new HashMap<>(), 0, new ArrayList<>());
        }

        @Override
        public void save() {}
    }

    static class FakePlayer extends Player {
        FakeInventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }

        @Override
        public void sendPacket(BasePacket p) {}
    }

    static class FakeInventory extends Inventory {
        int grants;

        FakeInventory() {
            super(null);
        }

        @Override
        public emu.grasscutter.game.inventory.InventoryAddResult addItems(Collection<GameItem> p, ActionReason r) {
            grants++;
            return emu.grasscutter.audit.InventoryResults.accepted(p);
        }

        @Override
        public boolean tryAddItems(Collection<GameItem> p, ActionReason r) {
            grants++;
            return true;
        }
    }
}
