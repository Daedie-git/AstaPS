package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.game.world.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.dungeons.*;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.data.ScriptArgs;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

public class WorldQuestAuditTest {
    @Test void hourTimersUseGameHoursAndKeepUnsetSemantics() {
        var world = allocate(World.class);
        set(world, "timeLocked", true);
        var player = allocate(Player.class);
        set(player, "world", world);
        var quest = new GameMainQuest();
        set(quest, "owner", player);
        set(quest, "timeVar", new long[]{0, -1, 1439});
        world.changeTime(60_000L);
        assertEquals(1, quest.getHoursSinceTimeVar(0));
        assertEquals(0, quest.getDaysSinceTimeVar(0));
        world.changeTime(1440_000L);
        assertEquals(24, quest.getHoursSinceTimeVar(0));
        assertEquals(1, quest.getDaysSinceTimeVar(0));
        assertEquals(1, quest.getHoursSinceTimeVar(2));
        assertEquals(0, quest.getHoursSinceTimeVar(1));
    }

    @Test void unlockingTimeDoesNotIncludeFrozenInterval() {
        var world = allocate(QuietWorld.class);
        set(world, "host", allocate(Player.class));
        set(world, "players", new ArrayList<Player>());
        set(world, "timeLocked", true);
        set(world, "currentWorldTime", 1000L);
        set(world, "lastUpdateTime", 0L);
        world.now = 60_000;
        world.lockTime(false);
        assertEquals(1000, world.getWorldTime());
        world.now += 500;
        assertEquals(1500, world.getWorldTime());
        world.lockTime(true);
        world.now += 90_000;
        assertEquals(1500, world.getWorldTime());
        world.lockTime(false);
        assertEquals(1500, world.getWorldTime());
    }

    @Test void passConditionOwnsTheSameOnceOnlySettlementTransition() {
        var scene = allocate(QuietScene.class);
        scene.players = new ArrayList<>(); scene.manager = allocate(QuietScripts.class); scene.listeners = new ArrayList<>();
        var world = allocate(World.class); set(scene, "world", world);
        var server = allocate(emu.grasscutter.server.game.GameServer.class); set(world, "server", server);
        var system = allocate(DungeonSystem.class); set(server, "dungeonSystem", system);
        var type = emu.grasscutter.game.dungeons.enums.DungeonPassConditionType.DUNGEON_COND_FINISH_CHALLENGE;
        var handlers = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<emu.grasscutter.game.dungeons.handlers.DungeonBaseHandler>();
        handlers.put(type.ordinal(), new emu.grasscutter.game.dungeons.pass_condition.ConditionFinishChallenge()); set(system, "passCondHandlers", handlers);
        var dungeon = new DungeonManager(scene, new DungeonData());
        var condition = new emu.grasscutter.data.excels.dungeon.DungeonPassConfigData.DungeonPassCondition();
        set(condition, "condType", type); set(condition, "param", new int[]{1});
        dungeon.getPassConfigData().setConds(List.of(condition));
        set(dungeon.getPassConfigData(), "logicType", emu.grasscutter.game.quest.enums.LogicType.LOGIC_AND);
        set(dungeon, "finishedConditions", new int[1]);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        scene.listeners.add((manager, reason) -> { count.incrementAndGet(); manager.triggerEvent(type, 1, 0); });
        dungeon.triggerEvent(type, 1, 0);
        dungeon.finishDungeon();
        assertTrue(dungeon.isFinishedSuccessfully());
        assertEquals(1, count.get());
    }

    @Test void settlementIsOnceEvenWithReentrantAndRepeatedCalls() {
        var scene = allocate(QuietScene.class);
        scene.players = new ArrayList<>();
        scene.manager = allocate(QuietScripts.class);
        scene.listeners = new ArrayList<>();
        var dungeon = new DungeonManager(scene, new DungeonData());
        var count = new java.util.concurrent.atomic.AtomicInteger();
        scene.listeners.add((manager, reason) -> {
            if (count.incrementAndGet() == 1) manager.finishDungeon();
        });
        dungeon.finishDungeon();
        dungeon.finishDungeon();
        assertEquals(1, count.get());
    }

    public static class QuietWorld extends World {
        public QuietWorld() { super((Player) null); }
        long now;
        @Override protected long nowMillis() { return now; }
        @Override public void updateTime() {}
    }
    public static class QuietScene extends Scene {
        public QuietScene() { super(null, null); }
        List<Player> players;
        SceneScriptManager manager;
        List<DungeonSettleListener> listeners;
        @Override public List<Player> getPlayers() { return players; }
        @Override public SceneScriptManager getScriptManager() { return manager; }
        @Override public List<DungeonSettleListener> getDungeonSettleListeners() { return listeners; }
    }
    public static class QuietScripts extends SceneScriptManager {
        public QuietScripts() { super(null); }
        @Override public Future<?> callEvent(ScriptArgs args) { return CompletableFuture.completedFuture(null); }
    }
}
