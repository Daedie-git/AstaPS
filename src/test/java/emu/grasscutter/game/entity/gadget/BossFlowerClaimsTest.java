package emu.grasscutter.game.entity.gadget;

import static emu.grasscutter.audit.MultiplayerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.managers.ResinManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.*;
import emu.grasscutter.scripts.data.*;
import java.util.*;
import org.junit.jupiter.api.*;

final class BossFlowerClaimsTest {
    BP a, b; BS scene; BG gadget; Chest chest; boolean oldScript;
    @BeforeEach void setup() {
        oldScript = Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        Grasscutter.getConfig().server.game.enableScriptInBigWorld = true;
        a = player(100); b = player(200);
        scene = allocate(BS.class); scene.players = new ArrayList<>(List.of(a, b));
        var world = world(a, true);
        field(scene, Scene.class, "world", world);
        a.world = world; b.world = world;
        field(scene, Scene.class, "scheduler", new emu.grasscutter.server.scheduler.ServerTaskScheduler());
        gadget = allocate(BG.class); gadget.scene = scene; gadget.meta = new SceneGadget();
        field(gadget, EntityGadget.class, "interactEnabled", true);
        scene.gadget = gadget;
        gadget.meta.boss_chest = new SceneBossChest(); gadget.meta.boss_chest.resin = 40;
        chest = new Chest(gadget);
        field(gadget, EntityGadget.class, "content", chest);
        info();
    }
    @AfterEach void restore() { Grasscutter.getConfig().server.game.enableScriptInBigWorld = oldScript; }
    BP player(int uid) {
        BP p = allocate(BP.class); field(p, Player.class, "id", uid); p.uid = uid; p.server = server(); p.sent = new ArrayList<>(); p.resin = new Resin(p);
        p.position = new emu.grasscutter.game.world.Position();
        p.rotation = new emu.grasscutter.game.world.Position();
        return p;
    }
    BossChestInfoOuterClass.BossChestInfo info() {
        var builder = SceneGadgetInfoOuterClass.SceneGadgetInfo.newBuilder(); chest.onBuildProto(builder);
        return builder.getBossChest();
    }
    void claim(BP p) {
        assertSame(gadget, scene.getEntityById(gadget.getId()));
        assertSame(scene, gadget.getScene());
        assertTrue(scene.getPlayers().contains(p));
        chest.onInteract(p, GadgetInteractReqOuterClass.GadgetInteractReq.newBuilder()
            .setOpType(InterOpTypeOuterClass.InterOpType.InterOpType_INTER_OP_FINISH).build());
    }
    @Test void eachEligiblePlayerPaysOnceAndFlowerSurvivesFirstClaim() {
        claim(a);
        assertEquals(0, gadget.state, "first claimant must not globally open flower");
        assertEquals(0, scene.removals); assertEquals(List.of(200), info().getRemainUidListList());
        claim(a); assertEquals(40, a.resin.spent); assertEquals(1, chest.grants.get(100));
        claim(b); assertEquals(40, b.resin.spent); assertEquals(1, chest.grants.get(200));
        assertEquals(102, gadget.state); assertEquals(1, scene.removals);
        assertTrue(info().getRemainUidListList().isEmpty());
    }
    @Test void everyPaidClaimantGetsProgressCreditOnce() {
        claim(a); assertEquals(Map.of(100, 1), chest.credits);
        claim(a); claim(b);
        assertEquals(Map.of(100, 1, 200, 1), chest.credits);
    }
    @Test void failedPaymentPreservesEligibilityAndRetryWorks() {
        b.resin.available = false; claim(b);
        assertEquals(List.of(100, 200), info().getRemainUidListList()); assertTrue(chest.grants.isEmpty());
        b.resin.available = true; claim(b);
        assertEquals(List.of(100), info().getRemainUidListList()); assertEquals(0, scene.removals);
    }
    @Test void lateArrivalIsNotEligibleAndCannotPay() {
        BP late = player(300); scene.players.add(late); claim(late);
        assertEquals(0, late.resin.spent); assertFalse(chest.grants.containsKey(300));
        assertEquals(List.of(100, 200), info().getQualifyUidListList());
    }
    @Test void advertisementDoesNotWaitOnRewardMutationMonitor() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            synchronized (chest) {
                var advertisement = executor.submit(this::info);
                assertDoesNotThrow(() -> advertisement.get(1, java.util.concurrent.TimeUnit.SECONDS),
                        "Scene advertisement must not wait for a chest lock held by a claimant");
            }
        }
    }
    @Test void claimedPlayersRetapThroughEntityGadgetIsAnswered() {
        claim(a);
        a.sent.clear();
        gadget.onInteract(a, GadgetInteractReqOuterClass.GadgetInteractReq.newBuilder()
            .setOpType(InterOpTypeOuterClass.InterOpType.InterOpType_INTER_OP_FINISH).build());
        assertEquals(1, a.sent.stream().filter(p -> p instanceof emu.grasscutter.server.packet.send.PacketGadgetInteractRsp).count(),
            "A player who already claimed must still get a response");
        assertEquals(40, a.resin.spent, "The re-tap must not pay again");
    }
    @Test void failedRewardDoesNotConsumeUidClaim() {
        chest.success = false; claim(a);
        assertEquals(0, a.resin.spent); assertEquals(List.of(100, 200), info().getRemainUidListList());
        chest.success = true; claim(a); assertEquals(List.of(200), info().getRemainUidListList());
    }
    static class BP extends P {
        Resin resin;
        @Override public ResinManager getResinManager() { return resin; }
    }
    static class Resin extends ResinManager {
        boolean available = true; int spent;
        Resin(Player p) { super(p); }
        @Override public boolean useResin(int amount) { if (!available) return false; spent += amount; return true; }
        @Override public void addResin(int amount) { spent -= amount; }
    }
    static class BS extends Scene {
        List<Player> players; BG gadget; int removals;
        BS() { super(null, null); }
        @Override public emu.grasscutter.game.managers.blossom.BlossomManager getBlossomManager() {
            return new emu.grasscutter.game.managers.blossom.BlossomManager(this);
        }
        @Override public List<Player> getPlayers() { return players; }
        @Override public GameEntity getEntityById(int id) { return removals == 0 ? gadget : null; }
        @Override public void removeEntity(GameEntity e, VisionTypeOuterClass.VisionType type) { removals++; }
    }
    static class BG extends EntityGadget {
        BS scene; SceneGadget meta; int state;
        BG() { super(null, 0, new emu.grasscutter.game.world.Position()); }
        @Override public Scene getScene() { return scene; }
        @Override public SceneGadget getMetaGadget() { return meta; }
        @Override protected void creditInteraction(Player player) {}
        @Override public int getState() { return state; }
        @Override public void updateState(int state) { this.state = state; }
    }
    static class Chest extends GadgetChest {
        boolean success = true; Map<Integer, Integer> grants = new HashMap<>();
        Map<Integer, Integer> credits = new HashMap<>();
        @Override protected void creditClaim(Player player) { credits.merge(player.getUid(), 1, Integer::sum); }
        Chest(EntityGadget g) { super(g); }
        @Override protected boolean grantBossChestFallback(Player p, SceneGadget m) {
            if (success) grants.merge(p.getUid(), 1, Integer::sum); return success;
        }
    }
}
