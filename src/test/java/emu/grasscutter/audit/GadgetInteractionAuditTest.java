package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.GadgetInteractReqOuterClass.GadgetInteractReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.recv.HandlerGadgetInteractReq;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class GadgetInteractionAuditTest {
    @Test void mismatchAndDisabledCannotCreditButStartAndFinishCan() {
        var gadget = allocate(CreditedGadget.class);
        var content = new RecordingContent(gadget);
        set(gadget, "content", content);
        var player = allocate(Player.class);
        var valid = GadgetInteractReq.newBuilder().setGadgetId(456);
        set(gadget, "interactEnabled", true);
        gadget.onInteract(player, valid.setGadgetId(999).build());
        assertEquals(0, gadget.credits);
        valid.setGadgetId(456);
        set(gadget, "interactEnabled", false);
        gadget.onInteract(player, valid.build());
        assertEquals(0, gadget.credits);
        set(gadget, "interactEnabled", true);
        gadget.onInteract(player, valid.setOpType(emu.grasscutter.net.proto.InterOpTypeOuterClass.InterOpType.InterOpType_INTER_OP_START).build());
        gadget.onInteract(player, valid.setOpType(emu.grasscutter.net.proto.InterOpTypeOuterClass.InterOpType.InterOpType_INTER_OP_FINISH).build());
        assertEquals(2, gadget.credits);
        assertEquals(2, content.calls);
    }
    @Test void enabledContentlessGadgetStillCreditsInteraction() {
        var gadget = allocate(CreditedGadget.class);
        set(gadget, "interactEnabled", true);
        gadget.onInteract(allocate(Player.class), GadgetInteractReq.newBuilder().setGadgetId(456).build());
        assertEquals(1, gadget.credits, "A valid gadget does not need a server content handler to advance a quest");
    }
    public static class CreditedGadget extends emu.grasscutter.game.entity.EntityGadget {
        public CreditedGadget() { super(null, 0, null); }
        int credits;
        @Override public int getGadgetId() { return 456; }
        @Override protected void creditInteraction(Player player) { credits++; }
    }
    static class RecordingContent extends emu.grasscutter.game.entity.gadget.GadgetContent {
        RecordingContent(emu.grasscutter.game.entity.EntityGadget gadget) { super(gadget); }
        int calls;
        @Override public boolean onInteract(Player player, GadgetInteractReq req) { calls++; return false; }
        @Override public void onBuildProto(emu.grasscutter.net.proto.SceneGadgetInfoOuterClass.SceneGadgetInfo.Builder builder) {}
    }
    @Test void realOfferingHelperMustPassEnabledAdmissionBeforeCredit() throws Exception {
        var helper = emu.grasscutter.game.entity.gadget.OfferingHelper.class;
        var levelsField = helper.getDeclaredField("LEVELS"); levelsField.setAccessible(true);
        var takenField = helper.getDeclaredField("TAKEN"); takenField.setAccessible(true);
        var levels = (java.util.Map) levelsField.get(null);
        var taken = (java.util.Map) takenField.get(null);
        var player = allocate(HelperPlayer.class);
        // Preloaded synthetic progress prevents the real helper from touching progress files.
        var oldLevels = levels.put(player.getUid(), new java.util.concurrent.ConcurrentHashMap<>());
        var oldTaken = taken.put(player.getUid(), new java.util.concurrent.ConcurrentHashMap<>());
        try {
            var gadget = allocate(OfferingGadget.class);
            var req = GadgetInteractReq.newBuilder().setGadgetId(gadget.getGadgetId()).build();
            set(gadget, "interactEnabled", false);
            gadget.onInteract(player, req);
            assertEquals(0, gadget.credits, "Disabled offering must not receive quest credit");
            assertEquals(0, player.packets, "Disabled helper must not dispatch UI responses");
            set(gadget, "interactEnabled", true);
            gadget.onInteract(player, req);
            assertEquals(1, gadget.credits, "Enabled offering with unset content remains accepted");
            assertTrue(player.packets > 0);
        } finally {
            if (oldLevels == null) levels.remove(player.getUid()); else levels.put(player.getUid(), oldLevels);
            if (oldTaken == null) taken.remove(player.getUid()); else taken.put(player.getUid(), oldTaken);
        }
    }
    @Test void disabledAndriusCannotReachStartHelper() {
        var gadget = allocate(AndriusGadget.class);
        var player = allocate(HelperPlayer.class);
        gadget.onInteract(player, GadgetInteractReq.newBuilder().setGadgetId(gadget.getGadgetId()).build());
        assertEquals(0, player.sceneReads, "Disabled trial must reject before start-helper admission");
        assertEquals(0, gadget.credits);
    }
    public static class HelperPlayer extends Player {
        int packets, sceneReads;
        @Override public int getUid() { return Integer.MAX_VALUE - 101; }
        @Override public void sendPacket(emu.grasscutter.net.packet.BasePacket packet) { packets++; }
        @Override public Scene getScene() { sceneReads++; return null; }
    }
    public static class OfferingGadget extends CreditedGadget {
        @Override public int getGadgetId() {
            return emu.grasscutter.game.entity.gadget.OfferingHelper.GADGET_SACRED_SAKURA;
        }
    }
    public static class AndriusGadget extends CreditedGadget {
        @Override public int getGroupId() { return emu.grasscutter.game.world.AndriusTrialStartHelper.GROUP_ID; }
        @Override public int getConfigId() { return emu.grasscutter.game.world.AndriusTrialStartHelper.SWORD_CONFIG_ID; }
    }

    @Test void missingEntityCannotCreditClientSuppliedGadgetId() {
        var player = allocate(Player.class);
        var scene = allocate(Scene.class);
        set(scene, "entities", new HashMap<>());
        set(scene, "weaponEntities", new HashMap<>());
        set(scene, "players", java.util.List.of());
        var world = allocate(emu.grasscutter.game.world.World.class);
        set(world, "entity", allocate(emu.grasscutter.game.entity.EntityWorld.class));
        set(scene, "world", world);
        set(player, "scene", scene);
        var session = allocate(GameSession.class);
        set(session, "player", player);
        var req = GadgetInteractReq.newBuilder().setGadgetEntityId(123).setGadgetId(456).build();
        // No quest manager: any attempted credit fails rather than disappearing asynchronously.
        assertDoesNotThrow(() -> new HandlerGadgetInteractReq().handle(session, null, req.toByteArray()));
    }
}
