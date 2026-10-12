package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.game.home.HomeWorld;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.SceneTransToPointReqOuterClass.SceneTransToPointReq;
import emu.grasscutter.server.event.player.PlayerTeleportEvent.TeleportType;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.recv.HandlerSceneTransToPointReq;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import org.junit.jupiter.api.Test;

class WaypointAuditTest {
    @Test void clientWaypointRespectsRequesterDiscoveryAndForceLocks() throws Exception {
        var player = allocate(Player.class);
        set(player, "unlockedScenePoints", new Int2ObjectOpenHashMap<Set<Integer>>());
        set(player, "forceLockedScenePoints", new Int2ObjectOpenHashMap<Set<Integer>>());
        var world = allocate(MovingWorld.class);
        set(player, "world", world);
        var home = allocate(HomeWorld.class);
        set(home, "players", List.of());
        set(player, "curHomeWorld", home);
        var session = allocate(QuietSession.class);
        set(session, "player", player);
        var point = new PointData(); point.setId(54321);
        set(point, "tranPos", new Position());
        int key = (3 << 16) + point.getId();
        var old = GameData.getScenePointEntryMap().put(key, new ScenePointEntry(3, point));
        var req = SceneTransToPointReq.newBuilder().setSceneId(3).setPointId(point.getId()).build();
        try {
            var handler = new HandlerSceneTransToPointReq();
            handler.handle(session, null, req.toByteArray());
            assertEquals(0, world.transfers);
            player.getUnlockedScenePoints(3).add(point.getId());
            handler.handle(session, null, req.toByteArray());
            assertEquals(1, world.transfers);
            player.getForceLockedScenePoints(3).add(point.getId());
            handler.handle(session, null, req.toByteArray());
            assertEquals(1, world.transfers);
            player.getForceLockedScenePoints(3).remove(point.getId());
            handler.handle(session, null, req.toByteArray());
            assertEquals(2, world.transfers);
        } finally {
            if (old == null) GameData.getScenePointEntryMap().remove(key);
            else GameData.getScenePointEntryMap().put(key, old);
        }
    }
    public static class QuietSession extends GameSession {
        public QuietSession() { super(null); }
        @Override public void send(BasePacket packet) {}
    }
    public static class MovingWorld extends World {
        public MovingWorld() { super((Player)null); }
        int transfers;
        @Override public boolean transferPlayerToScene(Player p, int scene, TeleportType type, Position pos) { transfers++; return true; }
    }
}
