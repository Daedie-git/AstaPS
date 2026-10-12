package emu.grasscutter.server.packet.recv;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.game.battlepass.*;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.BattlePassRewardTagOuterClass.BattlePassRewardTag;
import emu.grasscutter.net.proto.BattlePassRewardTakeOptionOuterClass.BattlePassRewardTakeOption;
import emu.grasscutter.net.proto.TakeBattlePassRewardReqOuterClass.TakeBattlePassRewardReq;
import emu.grasscutter.server.game.GameSession;

import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.*;

class BattlePassClaimReplayTest {
    @Test
    void selectableDuplicateAndInvalidReplayDoNotPayOrEraseMarker() throws Exception {
        var gson = new Gson();
        var oldRow =
                GameData.getBattlePassRewardDataMap()
                        .put(
                                101,
                                gson.fromJson(
                                        "{\"indexId\":1,\"level\":1,\"freeRewardIdList\":[991450]}",
                                        BattlePassRewardData.class));
        var oldChest =
                GameData.getItemDataMap()
                        .put(
                                991451,
                                gson.fromJson(
                                        "{\"id\":991451,\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_SELECTABLE_CHEST\",\"itemUse\":[{\"useOp\":\"ITEM_USE_ADD_SELECT_ITEM\",\"useParam\":[\"201\"]}]}",
                                        ItemData.class));
        var oldItem =
                GameData.getItemDataMap()
                        .put(
                                201,
                                gson.fromJson(
                                        "{\"id\":201,\"itemType\":\"ITEM_VIRTUAL\"}",
                                        ItemData.class));
        var reward = new RewardData();
        reward.rewardId = 991450;
        reward.rewardItemList = List.of(new ItemParamData(991451, 1));
        var oldReward = GameData.getRewardDataMap().put(991450, reward);
        var p = allocate(FakePlayer.class);
        p.inv = allocate(FakeInventory.class);
        p.manager = new FakeManager(p);
        var level = BattlePassManager.class.getDeclaredField("level");
        level.setAccessible(true);
        level.setInt(p.manager, 1);
        var tag = BattlePassRewardTag.newBuilder().setRewardId(991450).setLevel(1);
        var option = BattlePassRewardTakeOption.newBuilder().setTag(tag).setOptionIdx(1).build();
        var session = new FakeSession(p);
        var handler = new HandlerTakeBattlePassRewardReq();
        try {
            handler.handle(
                    session,
                    null,
                    TakeBattlePassRewardReq.newBuilder()
                            .addTakeOptionList(option)
                            .addTakeOptionList(option)
                            .build()
                            .toByteArray());
            assertEquals(1, p.inv.grants);
            assertTrue(p.manager.getTakenRewards().containsKey(991450));
            handler.handle(
                    session,
                    null,
                    TakeBattlePassRewardReq.newBuilder()
                            .addTakeOptionList(option.toBuilder().setOptionIdx(999))
                            .build()
                            .toByteArray());
            assertEquals(1, p.inv.grants);
            assertTrue(p.manager.getTakenRewards().containsKey(991450));
        } finally {
            if (oldRow == null) GameData.getBattlePassRewardDataMap().remove(101);
            else GameData.getBattlePassRewardDataMap().put(101, oldRow);
            if (oldChest == null) GameData.getItemDataMap().remove(991451);
            else GameData.getItemDataMap().put(991451, oldChest);
            if (oldItem == null) GameData.getItemDataMap().remove(201);
            else GameData.getItemDataMap().put(201, oldItem);
            if (oldReward == null) GameData.getRewardDataMap().remove(991450);
            else GameData.getRewardDataMap().put(991450, oldReward);
        }
    }

    static <T> T allocate(Class<T> t) throws Exception {
        var f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return t.cast(((Unsafe) f.get(null)).allocateInstance(t));
    }

    static class FakeManager extends BattlePassManager {
        FakeManager(Player p) {
            super(p);
        }

        @Override
        public void save() {}
    }

    static class FakePlayer extends Player {
        FakeManager manager;
        FakeInventory inv;

        @Override
        public BattlePassManager getBattlePassManager() {
            return manager;
        }

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
        public boolean tryAddItems(Collection<GameItem> items, ActionReason r) {
            grants++;
            return true;
        }

        @Override
        public boolean addItem(GameItem item, ActionReason r) {
            grants++;
            return true;
        }
    }

    static class FakeSession extends GameSession {
        Player p;

        FakeSession(Player p) {
            super(null);
            this.p = p;
        }

        @Override
        public Player getPlayer() {
            return p;
        }

        @Override
        public void send(BasePacket p) {}
    }
}
