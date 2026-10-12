package emu.grasscutter.server.packet.recv;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.grasscutter.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.game.battlepass.*;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.BattlePassRewardTagOuterClass.BattlePassRewardTag;
import emu.grasscutter.net.proto.BattlePassRewardTakeOptionOuterClass.BattlePassRewardTakeOption;
import emu.grasscutter.plugin.PluginManager;
import emu.grasscutter.server.event.*;
import emu.grasscutter.server.event.player.PlayerObtainItemEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;
import java.lang.reflect.*;
import java.util.*;

@ExtendWith(ServerResourceFixture.class)
class RewardReviewRegressionTest {
    final List<Runnable> restore = new ArrayList<>();
    P player;
    BattlePassRewardTakeOption option;
    @BeforeEach void setup() throws Exception {
        player = allocate(P.class);
        player.inv = new Inventory(player);
        player.codex = new PlayerCodex(player);
        player.bp = new BP(player);
        var level = BattlePassManager.class.getDeclaredField("level");
        level.setAccessible(true); level.setInt(player.bp, 1);
        put(GameData.getBattlePassRewardDataMap(), 101, new Gson().fromJson(
                "{\"indexId\":1,\"level\":1,\"freeRewardIdList\":[991850]}", BattlePassRewardData.class));
        option = BattlePassRewardTakeOption.newBuilder().setTag(BattlePassRewardTag.newBuilder()
                .setRewardId(991850).setLevel(1)).build();
    }
    @AfterEach void cleanup() { Collections.reverse(restore); restore.forEach(Runnable::run); }
    <T> void put(Map<Integer,T> map, int id, T row) {
        var old = map.put(id, row);
        restore.add(() -> { if (old == null) map.remove(id); else map.put(id, old); });
    }
    void item(int id, String fields) { put(GameData.getItemDataMap(), id,
            new Gson().fromJson("{\"id\":" + id + "," + fields + "}", ItemData.class)); }
    void reward(ItemParamData... params) {
        var row = new RewardData(); row.rewardId = 991850; row.rewardItemList = List.of(params);
        put(GameData.getRewardDataMap(), 991850, row);
    }
    void claim() { player.bp.takeReward(List.of(option)); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void ordinaryObtainListenerReclaimsOnceAndFullBagRemainsRetryable(boolean selectable) throws Exception {
        item(991851, "\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":10");
        reward(new ItemParamData(991851, 1));
        if (selectable) {
            item(991854, "\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_SELECTABLE_CHEST\",\"itemUse\":[{\"useOp\":\"ITEM_USE_ADD_SELECT_ITEM\",\"useParam\":[\"991851\"]}]");
            reward(new ItemParamData(991854, 1));
            option = option.toBuilder().setOptionIdx(1).build();
        }
        player.inv.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(0));
        claim();
        assertFalse(player.bp.getTakenRewards().containsKey(991850));
        assertTrue(player.inv.getItems().isEmpty());
        player.inv.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(10));
        var manager = allocate(PluginManager.class);
        var handlers = PluginManager.class.getDeclaredField("handlers"); handlers.setAccessible(true);
        handlers.set(manager, new LinkedHashMap<>());
        boolean[] invoked = {false};
        manager.registerListener(new EventHandler<>(PlayerObtainItemEvent.class).priority(HandlerPriority.NORMAL)
                .listener(event -> { if (!invoked[0]) { invoked[0] = true; claim(); } }));
        var field = Grasscutter.class.getDeclaredField("pluginManager"); field.setAccessible(true);
        var old = field.get(null); field.set(null, manager);
        try { claim(); claim(); } finally { field.set(null, old); }
        assertTrue(invoked[0]);
        assertEquals(1, player.inv.getItemCountById(991851));
        assertEquals(1, player.bp.getTakenRewards().size());
    }
    @Test void normalBattlePassDeliversTwoDistinctWeapons() {
        item(991852, "\"itemType\":\"ITEM_WEAPON\",\"stackLimit\":1");
        reward(new ItemParamData(991852, 2)); claim();
        assertEquals(2, player.inv.getItems().size());
        assertEquals(2, player.inv.getItems().values().stream().map(GameItem::getGuid).distinct().count());
        assertTrue(player.bp.getTakenRewards().containsKey(991850));
    }
    @Test void selectableBattlePassDeliversDeclaredTwoWeapons() {
        item(991852, "\"itemType\":\"ITEM_WEAPON\",\"stackLimit\":1");
        item(991854, "\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_SELECTABLE_CHEST\",\"itemUse\":[{\"useOp\":\"ITEM_USE_ADD_SELECT_ITEM\",\"useParam\":[\"991852\"]}]");
        reward(new ItemParamData(991854, 2));
        option = option.toBuilder().setOptionIdx(1).build();
        claim();
        assertEquals(2, player.inv.getItems().size());
        assertEquals(2, player.inv.getItems().values().stream().map(GameItem::getGuid).distinct().count());
    }
    @Test void oversizedBattlePassStackRejectsWithoutClippingOrMarker() {
        item(991853, "\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":10");
        reward(new ItemParamData(991853, 11)); claim();
        assertTrue(player.inv.getItems().isEmpty());
        assertFalse(player.bp.getTakenRewards().containsKey(991850));
    }
    @Test void mixedXpAndWeaponsRetainOrdinarySplitting() {
        item(102, "\"itemType\":\"ITEM_VIRTUAL\"");
        item(991852, "\"itemType\":\"ITEM_WEAPON\",\"stackLimit\":1");
        assertTrue(ClaimRewardDelivery.tryGrantParams(player.inv,
                List.of(new ItemParamData(102, 5), new ItemParamData(991852, 2)), null));
        assertEquals(5, player.xp);
        assertEquals(2, player.inv.getItems().size());
        assertEquals(2, player.inv.getItems().values().stream().map(GameItem::getGuid).distinct().count());
    }
    @Test void partialCallbackExceptionQuarantinesInsteadOfOfferingRetry() throws Exception {
        item(991851, "\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":10");
        reward(new ItemParamData(991851, 1));
        player.inv = new Inventory(player) {
            @Override public boolean tryAddItems(Collection<GameItem> items, emu.grasscutter.game.props.ActionReason reason) {
                super.tryAddItems(items, reason);
                throw new IllegalStateException("after insertion");
            }
        };
        assertThrows(IllegalStateException.class, this::claim);
        claim();
        assertEquals(1, player.inv.getItemCountById(991851));
        assertFalse(player.bp.getTakenRewards().containsKey(991850));
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((Unsafe)field.get(null)).allocateInstance(type));
    }
    static class BP extends BattlePassManager { BP(Player p) { super(p); } @Override public void save() {} }
    static class P extends Player {
        Inventory inv; BP bp; PlayerCodex codex; long guid; int xp;
        @Override public Inventory getInventory() { return inv; }
        @Override public BattlePassManager getBattlePassManager() { return bp; }
        @Override public PlayerCodex getCodex() { return codex; }
        @Override public long getNextGameGuid() { return ++guid; }
        @Override public void addExpDirectly(int gain) { xp += gain; }
        @Override public void sendPacket(BasePacket packet) {}
        @Override public void save() {}
    }
}
