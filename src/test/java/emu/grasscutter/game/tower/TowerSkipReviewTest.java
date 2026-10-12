package emu.grasscutter.game.tower;

import emu.grasscutter.game.dungeons.TowerDungeonSettleListener;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.tower.*;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.tower.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.server.game.GameServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;
import java.util.*;

@ExtendWith(ServerResourceFixture.class)
class TowerSkipReviewTest {
    final List<Runnable> restore = new ArrayList<>();
    P player; TowerManager manager;
    @BeforeEach void setup() throws Exception {
        player = allocate(P.class); player.data = new TowerData();
        player.data.recordMap = new HashMap<>(); player.data.lastScheduleId = 1;
        player.codex = new PlayerCodex(player); player.inv = new Inventory(player);
        player.server = allocate(GameServer.class);
        var field = GameServer.class.getDeclaredField("towerSystem"); field.setAccessible(true);
        field.set(player.server, allocate(T.class));
        manager = new TowerManager(player);
        put(GameData.getItemDataMap(), 991861, new Gson().fromJson(
                "{\"id\":991861,\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":100}", ItemData.class));
        var reward = new RewardData(); reward.rewardId = 991860; reward.rewardItemList = List.of(new ItemParamData(991861, 1));
        put(GameData.getRewardDataMap(), 991860, reward);
        for (int floor = 9; floor <= 12; floor++) {
            put(GameData.getTowerFloorDataMap(), floor, new Gson().fromJson(
                    "{\"floorId\":" + floor + ",\"floorIndex\":" + floor + ",\"levelGroupId\":" + floor
                    + ",\"rewardIdThreeStars\":991860,\"rewardIdSixStars\":991860,\"rewardIdNineStars\":991860}", TowerFloorData.class));
            for (int i = 0; i < 3; i++) put(GameData.getTowerLevelDataMap(), floor * 100 + i,
                    new Gson().fromJson("{\"levelId\":" + (floor * 100 + i) + ",\"levelGroupId\":" + floor
                            + ",\"levelIndex\":" + (i + 1) + ",\"firstPassRewardId\":991860}", TowerLevelData.class));
        }
    }
    @AfterEach void cleanup() { Collections.reverse(restore); restore.forEach(Runnable::run); }
    <T> void put(Map<Integer,T> map, int id, T value) {
        var old = map.put(id, value); restore.add(() -> { if (old == null) map.remove(id); else map.put(id, old); });
    }
    void previousClear(int floor) {
        var record = new TowerLevelRecord(floor);
        for (int i = 0; i < 3; i++) record.setLevelStars(floor * 100 + i, 3);
        player.data.recordMap.put(floor, record);
    }
    @Test void actualRolloverCompensationAndReclearPayEachChamberOnce() throws Exception {
        previousClear(12);
        var records = manager.getRecordMap();
        assertEquals(2, player.data.lastScheduleId);
        assertEquals(11, player.data.skipToFloorIndex);
        assertEquals(6, player.inv.getItemCountById(991861));
        var grant = TowerDungeonSettleListener.class.getDeclaredMethod("grantFirstPassRewards", TowerManager.class, int.class);
        grant.setAccessible(true);
        for (int floor = 9; floor <= 10; floor++) {
            player.data.currentFloorId = floor;
            for (int i = 0; i < 3; i++) {
                int level = floor * 100 + i;
                assertTrue(records.get(floor).isFirstPassPaid(level));
                grant.invoke(null, manager, level); grant.invoke(null, manager, level);
            }
        }
        assertEquals(6, player.inv.getItemCountById(991861));
        assertEquals(3, player.data.skipFloorState);
    }
    @Test void priorElevenSkipsOnlyNineAndRejectedCompensationRetries() {
        previousClear(11);
        player.inv.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(0));
        var records = manager.getRecordMap();
        assertEquals(10, player.data.skipToFloorIndex);
        assertEquals(2, player.data.skipFloorState);
        assertTrue(player.data.skipFloorGrantedRewards.isEmpty());
        for (int i = 0; i < 3; i++) assertFalse(records.get(9).isFirstPassPaid(900 + i));
        player.inv.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(1));
        manager.getRecordMap(); manager.getRecordMap();
        assertEquals(3, player.inv.getItemCountById(991861));
        assertEquals(3, player.data.skipFloorState);
        for (int i = 0; i < 3; i++) assertTrue(records.get(9).isFirstPassPaid(900 + i));
    }
    @Test void starTreasureCapacityFailurePreservesCursorAndRetryPaysOnce() {
        previousClear(9); player.data.lastScheduleId = 2;
        player.inv.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(0));
        assertFalse(manager.claimFloorStarReward(9));
        assertEquals(0, player.data.recordMap.get(9).getFloorStarRewardProgress());
        player.inv.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(1));
        assertTrue(manager.claimFloorStarReward(9)); assertFalse(manager.claimFloorStarReward(9));
        assertEquals(3, player.inv.getItemCountById(991861));
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return type.cast(((Unsafe) f.get(null)).allocateInstance(type));
    }
    static class T extends TowerSystem {
        T() { super(null); }
        @Override public TowerScheduleData getCurrentTowerScheduleData() {
            return new Gson().fromJson("{\"scheduleId\":2,\"entranceFloorId\":[]}", TowerScheduleData.class);
        }
        @Override public List<Integer> getScheduleFloors() { return List.of(9, 10, 11, 12); }
    }
    static class P extends Player {
        Inventory inv; TowerData data; GameServer server; PlayerCodex codex; long guid;
        @Override public Inventory getInventory() { return inv; }
        @Override public TowerData getTowerData() { return data; }
        @Override public GameServer getServer() { return server; }
        @Override public PlayerCodex getCodex() { return codex; }
        @Override public long getNextGameGuid() { return ++guid; }
        @Override public void save() {}
        @Override public void sendPacket(BasePacket packet) {}
    }
}
