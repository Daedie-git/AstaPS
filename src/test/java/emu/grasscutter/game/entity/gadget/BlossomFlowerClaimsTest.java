package emu.grasscutter.game.entity.gadget;

import static emu.grasscutter.audit.MultiplayerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.entity.gadget.chest.BossChestInteractHandler;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.managers.blossom.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.proto.*;
import emu.grasscutter.server.game.GameServer;
import java.util.*;
import org.junit.jupiter.api.*;

final class BlossomFlowerClaimsTest {
    private static final int ROW = 987654;
    private static final int ITEM = 987655;
    private final Map<Integer, Object> previous = new HashMap<>();
    private boolean oldScript;
    private S scene;
    private G gadget, origin;
    private GadgetChest chest;
    private P a, b;
    private BlossomManager manager;
    private List<BlossomActivity> active;
    private List<SpawnDataEntry> consumed;
    private final GadgetInteractReqOuterClass.GadgetInteractReq finish =
            GadgetInteractReqOuterClass.GadgetInteractReq.newBuilder()
                    .setOpType(InterOpTypeOuterClass.InterOpType.InterOpType_INTER_OP_FINISH).build();

    @BeforeEach void setup() throws Exception {
        oldScript = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
        var gson = new Gson();
        previous.put(1, GameData.getBlossomRefreshExcelConfigDataMap().put(ROW,
                gson.fromJson("{\"id\":987654,\"blossomChestId\":1,\"dropVec\":[{\"previewReward\":987654}]}", BlossomRefreshExcelConfigData.class)));
        var preview = new RewardPreviewData();
        field(preview, RewardPreviewData.class, "previewItemsArray",
                new emu.grasscutter.data.common.ItemParamData[] {new emu.grasscutter.data.common.ItemParamData(ITEM, 5)});
        previous.put(2, GameData.getRewardPreviewDataMap().put(ROW, preview));
        previous.put(3, GameData.getItemDataMap().put(ITEM,
                gson.fromJson("{\"id\":987655,\"itemType\":\"ITEM_MATERIAL\",\"stackLimit\":999}", ItemData.class)));
        var server = server();
        var data = allocate(WorldDataSystem.class);
        field(data, WorldDataSystem.class, "chestInteractHandlerMap",
                Map.of("SceneObj_Chest_Flora", new BossChestInteractHandler()));
        field(server, GameServer.class, "worldDataSystem", data);
        a = player(server, 100); b = player(server, 200);
        scene = allocate(S.class); scene.players = new ArrayList<>(List.of(a, b));
        field(scene, Scene.class, "scheduler", new emu.grasscutter.server.scheduler.ServerTaskScheduler());
        scene.world = world(a, true);
        field(scene.world, World.class, "server", server);
        a.actualScene = scene; b.actualScene = scene; a.world = scene.world; b.world = scene.world;
        manager = new BlossomManager(scene); scene.manager = manager;
        gadget = allocate(G.class); gadget.scene = scene;
        field(gadget, EntityGadget.class, "interactEnabled", true);
        gadget.meta = new emu.grasscutter.scripts.data.SceneGadget();
        gadget.meta.boss_chest = new emu.grasscutter.scripts.data.SceneBossChest();
        gadget.meta.boss_chest.resin = 20; // Generated blossom metadata deliberately has no group.
        gadget.data = gson.fromJson("{\"jsonName\":\"SceneObj_Chest_Flora\"}", GadgetData.class);
        chest = new TimedChest(gadget); gadget.content = chest; scene.gadget = gadget;
        gadget.setChildren(new ArrayList<>());
        origin = allocate(G.class); origin.scene = scene; origin.gadgetId = BlossomType.GOLD.getGadgetId();
        origin.setSpawnEntry(allocate(SpawnDataEntry.class));
        var activity = allocate(BlossomActivity.class);
        field(activity, BlossomActivity.class, "gadget", origin);
        field(activity, BlossomActivity.class, "chest", gadget);
        active = list(manager, "activeChests"); active.add(activity);
        consumed = list(manager, "blossomConsumed");
        list(manager, "createdEntity").add(origin);
        gadget.onCreate();
    }

    @SuppressWarnings("unchecked") private <T> List<T> list(Object target, String name) throws Exception {
        var f = BlossomManager.class.getDeclaredField(name); f.setAccessible(true);
        return (List<T>) f.get(target);
    }
    private P player(GameServer server, int uid) {
        P p = allocate(P.class); p.uid = uid; p.server = server; p.sent = new ArrayList<>();
        field(p, Player.class, "id", uid);
        p.position = new emu.grasscutter.game.world.Position();
        p.rotation = new emu.grasscutter.game.world.Position();
        p.resin = new BossFlowerClaimsTest.Resin(p); p.inventory = new RecordingInventory(p); return p;
    }
    @AfterEach void restore() {
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = oldScript;
        restore(GameData.getBlossomRefreshExcelConfigDataMap(), ROW, previous.get(1));
        restore(GameData.getRewardPreviewDataMap(), ROW, previous.get(2));
        restore(GameData.getItemDataMap(), ITEM, previous.get(3));
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private void restore(Map map, int key, Object value) {
        if (value == null) map.remove(key); else map.put(key, value);
    }
    @Test void mappedFlowerWithoutMetadataExpiresFromCreationBeforeAnyInteraction() throws Exception {
        gadget.meta = null;
        chest = new TimedChest(gadget); gadget.content = chest;
        gadget.onCreate();
        ((TimedChest)chest).now = java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
        tickRetirement();
        assertTrue(active.isEmpty()); assertTrue(listUncheckedCreated().isEmpty());
        assertEquals(1, scene.removals);
    }
    @Test void firstEmptySceneAdvertisementDoesNotFreezeAnEmptyEligibleRoster() throws Exception {
        scene.players.clear();
        chest = new TimedChest(gadget); gadget.content = chest;
        gadget.onCreate(); chest.onBuildProto(SceneGadgetInfoOuterClass.SceneGadgetInfo.newBuilder());
        tickRetirement(); assertEquals(0, scene.removals);
        scene.players.add(a);
        chest.onBuildProto(SceneGadgetInfoOuterClass.SceneGadgetInfo.newBuilder());
        assertTrue(chest.canInteract(a, finish));
    }
    @Test void abandonmentBeforeAnyClaimRetiresGeneratedFlowerAndRewardBacking() {
        scene.players.clear();
        tickRetirement();
        assertTrue(active.isEmpty()); assertTrue(consumed.isEmpty());
        assertTrue(listUncheckedCreated().isEmpty()); assertEquals(1, scene.removals);
        assertEquals(0, a.resin.spent); assertEquals(0, b.resin.spent);
        assertEquals(0, a.inventory.count); assertEquals(0, b.inventory.count);
        tickRetirement(); assertEquals(1, scene.removals);
    }
    @Test void unpaidGeneratedFlowerExpiresWithoutRequiringAPaidClaim() {
        a.resin.available = false; b.resin.available = false;
        chest.onInteract(a, finish); chest.onInteract(b, finish);
        tickRetirement(); assertEquals(1, active.size()); assertEquals(0, scene.removals);
        ((TimedChest)chest).now = java.util.concurrent.TimeUnit.MINUTES.toNanos(10) - 1;
        chest.onBuildProto(SceneGadgetInfoOuterClass.SceneGadgetInfo.newBuilder());
        tickRetirement(); assertEquals(1, active.size()); assertEquals(0, scene.removals);
        ((TimedChest)chest).now++;
        tickRetirement();
        assertTrue(active.isEmpty()); assertTrue(consumed.isEmpty());
        assertTrue(listUncheckedCreated().isEmpty()); assertEquals(1, scene.removals);
        assertEquals(0, a.resin.spent); assertEquals(0, b.resin.spent);
        assertEquals(0, a.inventory.count); assertEquals(0, b.inventory.count);
    }
    @Test void expiryPreservesPaidClaimWithoutPayingTheUnpaidPlayer() {
        chest.onInteract(a, finish);
        ((TimedChest)chest).now = java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
        tickRetirement();
        assertTrue(active.isEmpty()); assertTrue(consumed.isEmpty());
        assertEquals(20, a.resin.spent); assertEquals(5, a.inventory.count);
        assertEquals(0, b.resin.spent); assertEquals(0, b.inventory.count);
        assertEquals(1, scene.removals);
        chest.onInteract(a, finish); chest.onInteract(b, finish); tickRetirement();
        assertEquals(20, a.resin.spent); assertEquals(5, a.inventory.count);
        assertEquals(0, b.resin.spent); assertEquals(0, b.inventory.count);
        assertEquals(1, scene.removals);
    }
    void tickRetirement() { for (int i = 0; i < 5; i++) scene.getScheduler().runTasks(); }
    List<?> listUncheckedCreated() {
        try { return list(manager, "createdEntity"); } catch (Exception e) { throw new AssertionError(e); }
    }
    static class TimedChest extends GadgetChest {
        long now;
        TimedChest(EntityGadget gadget) { super(gadget); }
        @Override protected long monotonicNanos() { return now; }
    }
    @Test void realNonScriptClaimsRetainBackingUntilLastUid() {
        chest.onInteract(a, finish);
        assertEquals(1, active.size(), "pending player's reward backing must survive");
        assertTrue(consumed.isEmpty()); assertEquals(0, scene.removals);
        chest.onInteract(a, finish);
        assertEquals(20, a.resin.spent); assertEquals(5, a.inventory.count);
        chest.onInteract(b, finish);
        assertEquals(20, b.resin.spent); assertEquals(5, b.inventory.count);
        assertTrue(active.isEmpty()); assertEquals(List.of(origin.getSpawnEntry()), consumed);
        assertEquals(1, scene.removals);
        chest.onInteract(b, finish);
        assertEquals(20, b.resin.spent); assertEquals(5, b.inventory.count);
    }
    @Test void rejectedPaymentDoesNotFallThroughToScriptMetadata() {
        b.resin.available = false;
        assertDoesNotThrow(() -> chest.onInteract(b, finish));
        assertEquals(1, active.size()); assertEquals(0, b.inventory.count);
        b.resin.available = true; chest.onInteract(b, finish);
        assertEquals(5, b.inventory.count); assertEquals(1, active.size());
    }
    @Test void expirationRetiresBackingWithoutConsumingSpawn() {
        manager.recycleGadgetEntity(List.of(gadget));
        assertTrue(active.isEmpty()); assertTrue(consumed.isEmpty());
    }
    @Test void bossAdmissionDeniesReplayLateArrivalAndDisabledFlower() {
        assertTrue(chest.canInteract(a, finish));
        chest.onInteract(a, finish);
        assertFalse(chest.canInteract(a, finish));
        assertTrue(chest.canInteract(b, finish));
        P late = player(a.server, 300); scene.players.add(late);
        assertFalse(chest.canInteract(late, finish));
        scene.players.remove(b);
        assertFalse(chest.canInteract(b, finish));
        scene.players.add(b);
        field(gadget, EntityGadget.class, "interactEnabled", false);
        assertFalse(chest.canInteract(b, finish));
        chest.onInteract(b, finish);
        assertEquals(0, b.resin.spent);
        assertEquals(0, b.inventory.count);
    }
    @Test void actualEntityRemovalExpiresBackingIdempotently() {
        chest.onInteract(a, finish);
        gadget.onRemoved(); gadget.onRemoved();
        assertTrue(active.isEmpty()); assertTrue(consumed.isEmpty());
    }
    @Test void mappedBossAdmissionWorksWithoutBossMetadataAndStartDoesNotPay() {
        gadget.meta.boss_chest = null;
        assertTrue(chest.canInteract(a, finish));
        var start = finish.toBuilder()
                .setOpType(InterOpTypeOuterClass.InterOpType.InterOpType_INTER_OP_START).build();
        chest.onInteract(a, start);
        assertEquals(0, a.resin.spent); assertEquals(0, a.inventory.count);
        chest.onInteract(a, finish);
        assertFalse(chest.canInteract(a, start));
        assertFalse(chest.canInteract(a, finish));
        assertTrue(chest.canInteract(b, start));
    }
    static class P extends BossFlowerClaimsTest.BP {
        S actualScene; RecordingInventory inventory;
        @Override public Scene getScene() { return actualScene; }
        @Override public Inventory getInventory() { return inventory; }
    }
    static class RecordingInventory extends Inventory {
        int count;
        RecordingInventory(Player p) { super(p); }
        @Override public emu.grasscutter.game.inventory.InventoryAddResult addItems(Collection<GameItem> items, ActionReason reason) {
            items.forEach(item -> count += item.getCount());
            return emu.grasscutter.audit.InventoryResults.accepted(items);
        }
    }
    static class S extends BossFlowerClaimsTest.BS {
        emu.grasscutter.audit.MultiplayerFixtures.W world; BlossomManager manager;
        G gadget;
        @Override public GameEntity getEntityById(int id) { return removals == 0 ? gadget : null; }
        @Override public World getWorld() { return world; }
        @Override public BlossomManager getBlossomManager() { return manager; }
        @Override public emu.grasscutter.scripts.SceneScriptManager getScriptManager() { return allocate(QuietScripts.class); }
        @Override public void broadcastPacket(emu.grasscutter.net.packet.BasePacket packet) {}
    }
    static class QuietScripts extends emu.grasscutter.scripts.SceneScriptManager {
        QuietScripts() { super(null); }
        @Override public emu.grasscutter.scripts.data.SceneGroup getGroupById(int id) { return null; }
        @Override public java.util.concurrent.Future<?> callEvent(emu.grasscutter.scripts.data.ScriptArgs args) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }
    static class G extends BossFlowerClaimsTest.BG {
        GadgetData data; GadgetContent content; int gadgetId = 70210109;
        @Override public GadgetData getGadgetData() { return data; }
        @Override public GadgetContent getContent() { return content; }
        @Override public int getGadgetId() { return gadgetId; }
    }
}
