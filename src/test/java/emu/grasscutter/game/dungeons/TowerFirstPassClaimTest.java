package emu.grasscutter.game.dungeons;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.RewardData;
import emu.grasscutter.data.excels.tower.TowerLevelData;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.tower.*;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.data.excels.tower.TowerScheduleData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.*;

@ExtendWith(ServerResourceFixture.class)
class TowerFirstPassClaimTest {
    @Test
    void zeroStarRepeatPaysOnlyOnce() throws Exception {
        var oldItem =
                GameData.getItemDataMap()
                        .put(
                                201,
                                new Gson()
                                        .fromJson(
                                                "{\"id\":201,\"itemType\":\"ITEM_VIRTUAL\"}",
                                                emu.grasscutter.data.excels.ItemData.class));
        var player = allocate(RecordingPlayer.class);
        player.inv = allocate(RecordingInventory.class);
        player.server = allocate(GameServer.class);
        Field towerSystem = GameServer.class.getDeclaredField("towerSystem");
        towerSystem.setAccessible(true);
        towerSystem.set(player.server, allocate(RecordingTowerSystem.class));
        var manager = new RecordingTower(player);
        manager.records = new HashMap<>();
        manager.records.put(1, new TowerLevelRecord(1).setLevelStars(991620, 0));
        var level =
                new Gson()
                        .fromJson(
                                "{\"levelId\":991620,\"firstPassRewardId\":991621}",
                                TowerLevelData.class);
        var oldLevel = GameData.getTowerLevelDataMap().put(991620, level);
        var reward = new RewardData();
        reward.rewardId = 991621;
        reward.rewardItemList = List.of(new ItemParamData(201, 10));
        var oldReward = GameData.getRewardDataMap().put(991621, reward);
        Method claim =
                TowerDungeonSettleListener.class.getDeclaredMethod(
                        "grantFirstPassRewards", TowerManager.class, int.class);
        claim.setAccessible(true);
        try {
            claim.invoke(null, manager, 991620);
            claim.invoke(null, manager, 991620);
            assertEquals(1, player.inv.grants);
        } finally {
            if (oldItem == null) GameData.getItemDataMap().remove(201);
            else GameData.getItemDataMap().put(201, oldItem);
            if (oldLevel == null) GameData.getTowerLevelDataMap().remove(991620);
            else GameData.getTowerLevelDataMap().put(991620, oldLevel);
            if (oldReward == null) GameData.getRewardDataMap().remove(991621);
            else GameData.getRewardDataMap().put(991621, oldReward);
        }
    }

    static <T> T allocate(Class<T> type) throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return type.cast(((Unsafe) f.get(null)).allocateInstance(type));
    }

    static class RecordingPlayer extends Player {
        RecordingInventory inv;
        GameServer server;
        @Override public GameServer getServer() { return server; }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        @Override
        public void save() {}
    }

    static class RecordingInventory extends Inventory {
        int grants;

        RecordingInventory() {
            super(null);
        }

        @Override
        public emu.grasscutter.game.inventory.InventoryAddResult addItems(Collection<GameItem> items, ActionReason reason) {
            grants++;
            return emu.grasscutter.audit.InventoryResults.accepted(items);
        }

        @Override
        public boolean tryAddItems(Collection<GameItem> items, ActionReason reason) {
            grants++;
            return true;
        }
    }

    static class RecordingTowerSystem extends TowerSystem {
        RecordingTowerSystem() { super(null); }
        @Override public TowerScheduleData getCurrentTowerScheduleData() { return null; }
    }

    static class RecordingTower extends TowerManager {
        Map<Integer, TowerLevelRecord> records;

        RecordingTower(Player p) {
            super(p);
        }

        @Override
        public int getCurrentFloorId() {
            return 1;
        }

        @Override
        public Map<Integer, TowerLevelRecord> getRecordMap() {
            return records;
        }
    }
}
