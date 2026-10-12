package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.game.dungeons.DungeonSystem;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.HandlerPlayerEnterDungeonReq;
import emu.grasscutter.net.proto.PlayerEnterDungeonReqOuterClass.PlayerEnterDungeonReq;
import java.util.List;
import org.junit.jupiter.api.Test;

class DungeonEntryAuditTest {
    @Test void cancelledNormalAndTowerTransferPreserveSceneAndPendingTeleport() throws Exception {
        var field = emu.grasscutter.Grasscutter.class.getDeclaredField("pluginManager");
        field.setAccessible(true);
        var original = field.get(null);
        var plugins = allocate(emu.grasscutter.plugin.PluginManager.class);
        var cancellations = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new emu.grasscutter.server.event.EventHandler<>(emu.grasscutter.server.event.player.PlayerTeleportEvent.class)
                .listener(event -> { cancellations.incrementAndGet(); event.cancel(); });
        set(plugins, "handlers", java.util.Map.of(emu.grasscutter.server.event.player.PlayerTeleportEvent.class, List.of(handler)));
        field.set(null, plugins);
        try {
            withDungeon((player, system) -> {
                set(player, "world", allocate(World.class));
                var scene = player.getScene();
                scene.setPrevScene(7);
                var pending = new java.util.concurrent.CompletableFuture<Void>();
                player.setQueuedTeleport(pending);
                assertFalse(system.enterDungeon(player, 44, 987654, true));
                assertFalse(system.handoffDungeon(player, 987654, List.of()));
                var preparation = new java.util.concurrent.atomic.AtomicInteger();
                assertFalse(system.handoffDungeon(player, 987654, List.of(), preparation::incrementAndGet));
                assertEquals(0, preparation.get());
                assertEquals(3, cancellations.get());
                assertSame(scene, player.getScene());
                assertEquals(7, scene.getPrevScene());
                assertSame(pending, player.getQueuedTeleport());
                assertFalse(pending.isCancelled());
            });
        } finally { field.set(null, original); }
    }
    @Test void missingDestinationRejectsBeforeReadingStartMetadata() {
        withDungeon((player, system) -> {
            var data = new NoStartMetadata(); set(data, "id", 987654); set(data, "sceneId", 876699);
            GameData.getDungeonDataMap().put(987654, data);
            set(player, "world", allocate(World.class));
            var original = player.getScene();
            assertDoesNotThrow(() -> assertFalse(system.enterDungeon(player, 44, 987654, true)));
            assertSame(original, player.getScene());
        });
    }
    @Test void supportedDungeonLevelRejectsBeforeEntry() {
        withDungeon((player, system) -> {
            var data = GameData.getDungeonDataMap().get(987654);
            set(data, "limitLevel", 10);
            var point = new emu.grasscutter.data.common.PointData();
            point.setId(987);
            set(point, "dungeonIds", new int[]{987654});
            set(point, "pos", new Position());
            set(point, "tranPos", new Position());
            int key = (3 << 16) + 987;
            var old = GameData.getScenePointEntryMap().put(key,
                    new emu.grasscutter.data.binout.ScenePointEntry(3, point));
            try {
                assertFalse(emu.grasscutter.game.dungeons.DomainDungeonHelper.canEnterDungeonFromOpenWorld(player, 987, 987654));
                set(data, "limitLevel", 0);
                var entry = new emu.grasscutter.data.excels.dungeon.DungeonEntryData();
                entry.setSceneId(3); entry.setDungeonEntryId(987);
                var level = new emu.grasscutter.data.excels.dungeon.DungeonEntryData.DungeonEntryCondition();
                set(level, "type", emu.grasscutter.game.dungeons.enums.DungeonEntrySatisfiedConditionType.DUNGEON_ENTRY_CONDITION_LEVEL);
                set(level, "param1", 1);
                var quest = new emu.grasscutter.data.excels.dungeon.DungeonEntryData.DungeonEntryCondition();
                set(quest, "type", emu.grasscutter.game.dungeons.enums.DungeonEntrySatisfiedConditionType.DUNGEON_ENTRY_CONDITION_QUEST);
                set(quest, "param1", 999999);
                entry.setSatisfiedCond(List.of(level, quest));
                var oldEntry = GameData.getDungeonEntryDataMap().put(987666, entry);
                try {
                    entry.setCondComb(emu.grasscutter.game.dungeons.enums.DungeonEntryCondCombType.DUNGEON_ENTRY_COND_COMB_LOGIC_AND);
                    assertFalse(emu.grasscutter.game.dungeons.DomainDungeonHelper.canEnterDungeonFromOpenWorld(player, 987, 987654));
                    entry.setCondComb(emu.grasscutter.game.dungeons.enums.DungeonEntryCondCombType.DUNGEON_ENTRY_COND_COMB_LOGIC_OR);
                    assertTrue(emu.grasscutter.game.dungeons.DomainDungeonHelper.canEnterDungeonFromOpenWorld(player, 987, 987654));
                    set(level, "param1", 10);
                    assertFalse(emu.grasscutter.game.dungeons.DomainDungeonHelper.canEnterDungeonFromOpenWorld(player, 987, 987654));
                    entry.setSatisfiedCond(List.of());
                    assertTrue(emu.grasscutter.game.dungeons.DomainDungeonHelper.canEnterDungeonFromOpenWorld(player, 987, 987654));
                    player.getPosition().set(new Position(1000, 1000, 1000));
                    assertFalse(emu.grasscutter.game.dungeons.DomainDungeonHelper.canEnterDungeonFromOpenWorld(player, 987, 987654));
                } finally {
                    if (oldEntry == null) GameData.getDungeonEntryDataMap().remove(987666);
                    else GameData.getDungeonEntryDataMap().put(987666, oldEntry);
                }
            } finally {
                if (old == null) GameData.getScenePointEntryMap().remove(key);
                else GameData.getScenePointEntryMap().put(key, old);
            }
        });
    }
    @Test void authorizedHandoffEndsOldChallengeBeforePreparingTheTransfer() {
        withDungeon((player, system) -> {
            var oldScene = allocate(ChallengeScene.class);
            set(player, "scene", oldScene);
            var world = allocate(PreparingWorld.class); set(player, "world", world);
            assertFalse(system.handoffDungeon(player, 987654, List.of(), () ->
                    assertEquals(1, oldScene.challengeReads, "Old challenge must close before transfer preparation")));
        });
    }
    public static class ChallengeScene extends OverworldScene {
        int challengeReads;
        @Override public SceneType getSceneType() { return SceneType.SCENE_DUNGEON; }
        @Override public emu.grasscutter.game.dungeons.challenge.WorldChallenge getChallenge() {
            challengeReads++; return null;
        }
    }
    public static class PreparingWorld extends RejectWorld {
        @Override public boolean transferPlayerToScene(Player player, int id, DungeonData data, Runnable prepare) {
            prepare.run(); return false;
        }
    }
    @Test void falseHandoffDoesNotReportSuccess() {
        withDungeon((player, system) -> assertFalse(system.handoffDungeon(player, 987654, List.of())));
    }
    @Test void failedEntryPreservesOriginMetadata() {
        withDungeon((player, system) -> {
            player.getScene().setPrevScene(7);
            player.setPrevSceneForDungeon(9);
            player.setPrevPosForDungeon(new Position(1, 2, 3));
            assertFalse(system.enterDungeon(player, 44, 987654, true));
            assertEquals(7, player.getScene().getPrevScene());
            assertEquals(9, player.getPrevSceneForDungeon());
            assertEquals(1, player.getPrevPosForDungeon().getX());
        });
    }
    @Test void normalClientEntryRejectsUnofferedPointBeforeTransfer() {
        withDungeon((player, system) -> {
            var server = allocate(GameServer.class);
            set(server, "dungeonSystem", system);
            var session = allocate(GameSession.class);
            set(session, "player", player);
            set(session, "server", server);
            var request = PlayerEnterDungeonReq.newBuilder().setPointId(987).setDungeonId(987654).build();
            try { new HandlerPlayerEnterDungeonReq().handle(session, null, request.toByteArray()); }
            catch (Exception e) { throw new AssertionError(e); }
            assertEquals(0, ((RejectWorld)player.getWorld()).transfers);
        });
    }
    static void withDungeon(java.util.function.BiConsumer<Player, DungeonSystem> action) {
        var data = new SyntheticDungeon();
        set(data, "id", 987654);
        set(data, "sceneId", 987655);
        var old = GameData.getDungeonDataMap().put(987654, data);
        var destination = new emu.grasscutter.data.excels.scene.SceneData();
        set(destination, "id", 987655);
        var oldScene = GameData.getSceneDataMap().put(987655, destination);
        try {
            var player = allocate(QuietPlayer.class);
            var scene = allocate(OverworldScene.class);
            set(player, "scene", scene);
            set(player, "position", new Position());
            set(player, "world", allocate(RejectWorld.class));
            action.accept(player, allocate(DungeonSystem.class));
        } finally {
            if (old == null) GameData.getDungeonDataMap().remove(987654);
            else GameData.getDungeonDataMap().put(987654, old);
            if (oldScene == null) GameData.getSceneDataMap().remove(987655);
            else GameData.getSceneDataMap().put(987655, oldScene);
        }
    }
    public static class NoStartMetadata extends DungeonData {
        @Override public Position getStartPosition() { throw new AssertionError("Missing destination must reject before reading scene scripts"); }
    }
    public static class SyntheticDungeon extends DungeonData {
        @Override public Position getStartPosition() { return new Position(); }
        @Override public Position getStartRotation() { return new Position(); }
    }
    public static class QuietPlayer extends Player {
        public QuietPlayer() { super(); }
        @Override public int getLevel() { return 1; }
        @Override public void sendPacket(BasePacket packet) {}
    }
    public static class OverworldScene extends Scene {
        public OverworldScene() { super(null, null); }
        @Override public int getId() { return 3; }
        @Override public SceneType getSceneType() { return SceneType.SCENE_WORLD; }
    }
    public static class RejectWorld extends World {
        public RejectWorld() { super((Player)null); }
        int transfers;
        @Override public boolean transferPlayerToScene(Player player, int id, DungeonData data) { transfers++; return false; }
        @Override public boolean transferPlayerToScene(Player player, int id, DungeonData data, Runnable prepare) {
            transfers++; return false;
        }
    }
}
