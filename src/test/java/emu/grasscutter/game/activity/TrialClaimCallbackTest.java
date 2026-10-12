package emu.grasscutter.game.activity;

import emu.grasscutter.game.activity.trialavatar.*;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.RewardData;
import emu.grasscutter.game.activity.*;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.ActivityType;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.plugin.PluginManager;
import emu.grasscutter.server.event.*;
import emu.grasscutter.server.event.player.PlayerObtainItemEvent;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
class TrialClaimCallbackTest {
    static final int A = 991901, B = 991902, C = 991903;
    final List<Runnable> restore = new ArrayList<>();
    final TrialAvatarActivityHandler handler = new TrialAvatarActivityHandler();
    P player;
    Data data;

    @BeforeEach
    void setup() throws Exception {
        player = allocate(P.class);
        player.inventory = new Inventory(player);
        player.codex = new PlayerCodex(player);
        player.activities = allocate(Activities.class);
        data = new Data();
        player.activities.data = data;
        var a = TrialAvatarPlayerData.RewardInfoItem.create(A, A);
        var b = TrialAvatarPlayerData.RewardInfoItem.create(B, B);
        var c = TrialAvatarPlayerData.RewardInfoItem.create(C, C);
        a.setPassedDungeon(true);
        b.setPassedDungeon(true);
        data.setDetail(TrialAvatarPlayerData.of().rewardInfoList(List.of(a, b, c)).build());
        for (int id : List.of(A, B, C)) {
            put(GameData.getItemDataMap(), id, new Gson().fromJson(
                    "{\"id\":" + id + ",\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":10}", ItemData.class));
            var reward = new RewardData();
            reward.rewardId = id;
            reward.rewardItemList = List.of(new ItemParamData(id, 1));
            put(GameData.getRewardDataMap(), id, reward);
        }
    }

    <T> void put(Map<Integer, T> map, int id, T value) {
        var old = map.put(id, value);
        restore.add(() -> { if (old == null) map.remove(id); else map.put(id, old); });
    }

    @AfterEach
    void cleanup() {
        Collections.reverse(restore);
        restore.forEach(Runnable::run);
    }

    AutoCloseable onObtain(Runnable action) throws Exception {
        var manager = allocate(PluginManager.class);
        var handlers = PluginManager.class.getDeclaredField("handlers");
        handlers.setAccessible(true);
        handlers.set(manager, new LinkedHashMap<>());
        boolean[] called = {false};
        manager.registerListener(new EventHandler<>(PlayerObtainItemEvent.class)
                .priority(HandlerPriority.NORMAL).listener(event -> {
                    if (!called[0]) { called[0] = true; action.run(); }
                }));
        var field = Grasscutter.class.getDeclaredField("pluginManager");
        field.setAccessible(true);
        var previous = field.get(null);
        field.set(null, manager);
        return () -> field.set(null, previous);
    }

    @Test
    void differentTrialCallbackPreservesBothClaimsAndInterveningPassUpdate() throws Exception {
        boolean[] nestedClaim = {false};
        try (var listener = onObtain(() -> {
            nestedClaim[0] = handler.getReward(player, B);
            // A concurrent pass producer updates the authoritative JSON while A is in delivery.
            var pass = new Thread(() -> {
                synchronized (data) {
                    var fresh = handler.getTrialAvatarPlayerData(data);
                    fresh.getRewardInfo(C).setPassedDungeon(true);
                    data.setDetail(fresh);
                }
            });
            pass.start();
            try { pass.join(2000); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            assertFalse(pass.isAlive(), "Different pass updates must remain admissible");
        })) {
            assertTrue(handler.getReward(player, A));
        }
        assertTrue(nestedClaim[0], "The different eligible claim must be admitted");
        var fresh = handler.getTrialAvatarPlayerData(data);
        assertTrue(fresh.getRewardInfo(A).isReceivedReward());
        assertTrue(fresh.getRewardInfo(B).isReceivedReward());
        assertTrue(fresh.getRewardInfo(C).isPassedDungeon());
        assertFalse(fresh.getRewardInfo(C).isReceivedReward());
        assertEquals(1, player.inventory.getItemCountById(A));
        assertEquals(1, player.inventory.getItemCountById(B));
        assertFalse(handler.getReward(player, A));
        assertFalse(handler.getReward(player, B));
        assertEquals(1, player.inventory.getItemCountById(A));
        assertEquals(1, player.inventory.getItemCountById(B));
        assertEquals(2, data.saves);
    }

    @Test
    void sameTrialCallbackRemainsExcluded() throws Exception {
        boolean[] nestedClaim = {true};
        try (var listener = onObtain(() -> nestedClaim[0] = handler.getReward(player, A))) {
            assertTrue(handler.getReward(player, A));
        }
        assertFalse(nestedClaim[0]);
        assertFalse(handler.getReward(player, A));
        assertEquals(1, player.inventory.getItemCountById(A));
        assertTrue(handler.getTrialAvatarPlayerData(data).getRewardInfo(A).isReceivedReward());
    }

    @Test
    void capacityRejectionRetainsTrialEligibilityForOneRetry() {
        player.inventory.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(0));
        assertFalse(handler.getReward(player, A));
        assertFalse(handler.getTrialAvatarPlayerData(data).getRewardInfo(A).isReceivedReward());
        assertEquals(0, data.saves);
        player.inventory.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(1));
        assertTrue(handler.getReward(player, A));
        assertFalse(handler.getReward(player, A));
        assertEquals(1, player.inventory.getItemCountById(A));
        assertEquals(1, data.saves);
    }

    @Test
    void exceptionalPartialDeliveryStaysQuarantined() {
        player.inventory = new Inventory(player) {
            @Override public boolean tryAddItems(Collection<GameItem> items,
                    emu.grasscutter.game.props.ActionReason reason) {
                super.tryAddItems(items, reason);
                throw new IllegalStateException("after actual insertion");
            }
        };
        assertThrows(IllegalStateException.class, () -> handler.getReward(player, A));
        assertFalse(handler.getReward(player, A));
        assertEquals(1, player.inventory.getItemCountById(A));
        assertFalse(handler.getTrialAvatarPlayerData(data).getRewardInfo(A).isReceivedReward());
    }

    static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    static class Data extends PlayerActivityData {
        int saves;
        Data() { super(null, 0, 991900, new HashMap<>(), null, null, null); }
        @Override public void save() { saves++; }
    }

    static class Activities extends ActivityManager {
        Data data;
        Activities() { super(null); }
        @Override public Optional<PlayerActivityData> getPlayerActivityDataByActivityType(ActivityType type) {
            return type == ActivityType.NEW_ACTIVITY_TRIAL_AVATAR ? Optional.of(data) : Optional.empty();
        }
    }

    static class P extends Player {
        Inventory inventory;
        Activities activities;
        PlayerCodex codex;
        long guid;
        @Override public Inventory getInventory() { return inventory; }
        @Override public ActivityManager getActivityManager() { return activities; }
        @Override public PlayerCodex getCodex() { return codex; }
        @Override public long getNextGameGuid() { return ++guid; }
        @Override public void sendPacket(BasePacket packet) {}
        @Override public void save() {}
    }
}
