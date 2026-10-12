package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.config.Configuration;
import emu.grasscutter.data.excels.GadgetData;
import emu.grasscutter.game.entity.EntityGadget;
import emu.grasscutter.game.entity.gadget.GadgetChest;
import emu.grasscutter.game.entity.gadget.chest.NormalChestInteractHandler;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.GadgetInteractReqOuterClass.GadgetInteractReq;
import emu.grasscutter.net.proto.InterOpTypeOuterClass.InterOpType;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.data.SceneGroup;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChestAuditTest {
    @Test void mappedOrdinaryChestRejectsGuestAndAlreadyOpenedHost() {
        boolean scriptsEnabled = Configuration.GAME_INFO.enableScriptInBigWorld;
        Configuration.GAME_INFO.enableScriptInBigWorld = false;
        try {
            var host = allocate(LootPlayer.class);
            var guest = allocate(LootPlayer.class);
            var world = allocate(World.class);
            set(world, "host", host);
            set(host, "world", world); set(guest, "world", world);
            var scene = allocate(ChestScene.class);
            set(scene, "world", world);
            set(scene, "scheduler", new emu.grasscutter.server.scheduler.ServerTaskScheduler());
            set(scene, "scriptManager", allocate(NoGroups.class));
            var server = allocate(GameServer.class);
            set(world, "server", server);
            var worldData = allocate(WorldDataSystem.class);
            set(server, "worldDataSystem", worldData);
            // Mapped ordinary handler: its configured reward is irrelevant to current ordinary loot.
            set(worldData, "chestInteractHandlerMap", Map.of("mapped", new NormalChestInteractHandler(null)));
            var gadget = allocate(ChestGadget.class);
            set(gadget, "scene", scene);
            set(gadget, "interactEnabled", true); // Unsafe allocation skips the constructor default.
            var data = new GadgetData();
            set(data, "jsonName", "mapped");
            set(gadget, "gadgetData", data);
            var chest = new GadgetChest(gadget);
            var req = GadgetInteractReq.newBuilder().setOpType(InterOpType.InterOpType_INTER_OP_FINISH).build();
            chest.onInteract(guest, req);
            assertEquals(0, guest.lootCalls);
            assertEquals(0, gadget.getState());
            gadget.state = 102;
            chest.onInteract(host, req);
            assertEquals(0, host.lootCalls);
            gadget.state = 0;
            chest.onInteract(host, req);
            assertEquals(1, host.lootCalls);
            chest.onInteract(guest, req);
            chest.onInteract(host, req); // Entity still exists until delayed despawn.
            assertEquals(1, host.lootCalls);
            assertEquals(0, guest.lootCalls);
            gadget.state = 0; // Even an incidental state reset cannot reopen this runtime claim.
            chest.onInteract(host, req);
            assertEquals(1, host.lootCalls);
        } finally { Configuration.GAME_INFO.enableScriptInBigWorld = scriptsEnabled; }
    }
    public static class LootPlayer extends Player {
        @Override public void earnExp(int exp) { lootCalls++; }
        @Override public void sendPacket(BasePacket packet) {}
        int lootCalls;
    }
    public static class ChestGadget extends EntityGadget {
        public ChestGadget() { super(null, 0, null); }
        int state;
        @Override public int getState() { return state; }
        @Override public void updateState(int state) { this.state = state; }
    }
    public static class ChestScene extends Scene {
        public ChestScene() { super(null, null); }
        @Override public int getId() { return 3; }
    }
    public static class NoGroups extends SceneScriptManager {
        public NoGroups() { super(null); }
        @Override public SceneGroup getGroupById(int group) { return null; }
    }
}
