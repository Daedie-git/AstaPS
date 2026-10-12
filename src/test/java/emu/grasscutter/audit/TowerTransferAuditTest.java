package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.tower.TowerLevelData;
import emu.grasscutter.game.dungeons.DungeonSystem;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.tower.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.TowerEnterLevelRspOuterClass.TowerEnterLevelRsp;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.send.PacketTowerEnterLevelRsp;
import java.util.*;
import org.junit.jupiter.api.Test;

class TowerTransferAuditTest {
    @Test void rejectedTowerEntryNeverPreparesTeamOrSendsSkillLockAndRestoresSelection() throws Exception {
        var player = allocate(RecordingPlayer.class);
        player.packets = new ArrayList<>();
        var towerData = new TowerData();
        set(towerData, "currentFloorId", 3); set(towerData, "currentLevel", 1); set(towerData, "currentLevelId", 77);
        set(player, "towerData", towerData);
        set(player, "position", new Position());
        var tower = allocate(NoPreparationTower.class);
        set(tower, "player", player);
        set(tower, "awaitingTeamReconfigure", true);
        tower.level = new TowerLevelData(); set(tower.level, "dungeonId", 876543);
        var dungeon = new DungeonEntryAuditTest.SyntheticDungeon();
        set(dungeon, "id", 876543); set(dungeon, "sceneId", 876544);
        var old = GameData.getDungeonDataMap().put(876543, dungeon);
        var world = allocate(World.class); // Missing destination rejects before trusted preparation.
        set(player, "world", world);
        var server = allocate(GameServer.class);
        set(server, "dungeonSystem", allocate(DungeonSystem.class));
        var session = allocate(GameSession.class); set(session, "server", server); set(player, "session", session);
        try {
            tower.enterLevel(17);
            assertEquals(3, tower.getCurrentFloorId());
            var selection = TowerData.class.getDeclaredField("currentLevel");
            selection.setAccessible(true);
            assertEquals(1, selection.getInt(towerData));
            selection = TowerData.class.getDeclaredField("currentLevelId");
            selection.setAccessible(true);
            assertEquals(77, selection.getInt(towerData));
            assertTrue(tower.isAwaitingTeamReconfigure());
            assertEquals(1, player.packets.size());
            assertInstanceOf(PacketTowerEnterLevelRsp.class, player.packets.get(0));
            assertNotEquals(0, TowerEnterLevelRsp.parseFrom(player.packets.get(0).getData()).getRetcode());
        } finally {
            if (old == null) GameData.getDungeonDataMap().remove(876543);
            else GameData.getDungeonDataMap().put(876543, old);
        }
    }
    public static class RecordingPlayer extends Player {
        List<BasePacket> packets;
        @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
    }
    public static class NoPreparationTower extends TowerManager {
        public NoPreparationTower() { super(null); }
        TowerLevelData level;
        @Override public TowerLevelData getCurrentTowerLevelDataMap() { return level; }
        @Override public void ensureFloorStateForEnter() { set(getTowerData(), "currentFloorId", 4); }
        @Override public void notifyCurLevelRecordChange() { fail("Rejected entry must not prepare tower/team state"); }
    }
}
