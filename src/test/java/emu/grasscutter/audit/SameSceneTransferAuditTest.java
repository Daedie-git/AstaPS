package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.scene.SceneData;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.game.world.data.TeleportProperties;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.server.event.player.PlayerTeleportEvent.TeleportType;
import emu.grasscutter.net.proto.EnterTypeOuterClass.EnterType;
import emu.grasscutter.net.packet.BasePacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import org.junit.jupiter.api.Test;

class SameSceneTransferAuditTest {
    @Test void solePlayerScriptTransferKeepsRegistrationButDoesNotLeakEmptySceneHold() throws Exception {
        var player = allocate(QuietPlayer.class);
        var world = allocate(World.class); set(player, "world", world);
        set(player, "position", new Position()); set(player, "rotation", new Position());
        var team = allocate(TeamManager.class); set(team, "avatars", new ArrayList<>()); set(team, "gadgets", Set.of()); set(player, "teamManager", team);
        var scene = allocate(TrackingScene.class); set(scene, "world", world); set(scene, "players", new ArrayList<>(List.of(player))); set(scene, "entities", new HashMap<>());
        set(player, "scene", scene);
        var gate = Class.forName("emu.grasscutter.game.world.ScenePlayerEntryGate").getDeclaredConstructor(); gate.setAccessible(true);
        set(scene, "playerEntryCallbacks", gate.newInstance());
        var scripts = allocate(TrackingScripts.class); set(scene, "scriptManager", scripts);
        var scenes = new Int2ObjectOpenHashMap<Scene>(); scenes.put(3, scene); set(world, "scenes", scenes);
        var data = new SceneData(); set(data, "id", 3); set(scene, "sceneData", data);
        var old = GameData.getSceneDataMap().put(3, data);
        try {
            assertTrue(world.transferPlayerToScene(player, TeleportProperties.builder().sceneId(3)
                    .teleportType(TeleportType.SCRIPT).enterType(EnterType.EnterType_ENTER_GOTO)
                    .teleportTo(new Position(1, 2, 3)).build()));
            assertSame(scene, world.getScenes().get(3));
            assertSame(scene, player.getScene());
            assertEquals(0, scripts.destructions);
            scene.removePlayer(player); // Actual last-player departure must still destroy/deregister.
            assertEquals(1, scripts.destructions);
            assertFalse(world.getScenes().containsKey(3));
        } finally {
            if (old == null) GameData.getSceneDataMap().remove(3); else GameData.getSceneDataMap().put(3, old);
        }
    }
    public static class QuietPlayer extends Player {
        @Override public void sendPacket(BasePacket packet) {}
    }
    public static class TrackingScene extends Scene {
        public TrackingScene() { super(null, null); }
        @Override public int getId() { return 3; }
        @Override public emu.grasscutter.game.props.SceneType getSceneType() { return emu.grasscutter.game.props.SceneType.SCENE_WORLD; }
        @Override public void addPlayer(Player player) { getPlayers().add(player); player.setScene(this); }
        @Override public void saveGroups() {}
    }
    public static class TrackingScripts extends SceneScriptManager {
        public TrackingScripts() { super(null); }
        int destructions;
        @Override public synchronized void onDestroy() { destructions++; }
    }
}
