package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.game.dailytask.*;
import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.scheduler.ServerTaskScheduler;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommissionProgressAuditTest {
    @Test void realDeathsAdvanceMatchingWavesAndPayFourTasksAndBonusOnce() throws Exception {
        var serverField = Grasscutter.class.getDeclaredField("gameServer"); serverField.setAccessible(true);
        var originalServer = serverField.get(null);
        var cacheField = DailyTaskManager.class.getDeclaredField("GROUP_RESOURCE_SUPPORT_CACHE"); cacheField.setAccessible(true);
        @SuppressWarnings("unchecked") var cache = (Map<Integer, Boolean>)cacheField.get(null);
        var oldCache = new HashMap<Integer, Boolean>();
        var oldData = new HashMap<Integer, DailyTaskData>();
        var oldRewards = new HashMap<Integer, RewardPreviewData>();
        var scheduler = new ServerTaskScheduler();
        var server = allocate(GameServer.class); set(server, "scheduler", scheduler); serverField.set(null, server);
        var player = allocate(RewardPlayer.class);
        var inventory = allocate(RewardInventory.class); inventory.reasons = new ArrayList<>(); set(player, "inventory", inventory);
        var daily = allocate(RecordedDaily.class); set(daily, "player", player); set(player, "dailyTaskManager", daily);
        var world = allocate(World.class); set(world, "host", player);
        var scene = allocate(CommissionDeathAuditTest.CommissionScene.class);
        set(scene, "world", world); set(scene, "players", List.of()); set(scene, "deadSpawnedEntities", new HashSet<>());
        set(scene, "loadedGroups", Set.of()); set(scene, "scriptManager", allocate(CommissionDeathAuditTest.NoGroups.class));
        var tasks = new ArrayList<DailyTask>(); set(daily, "dailyTasks", tasks);
        try {
            for (int reward : new int[]{987650, 987651}) {
                var preview = new RewardPreviewData(); set(preview, "previewItemsArray", new ItemParamData[]{new ItemParamData(201, 1)});
                oldRewards.put(reward, GameData.getRewardPreviewDataMap().put(reward, preview));
            }
            for (int i = 0; i < 4; i++) {
                int id = 987600 + i, group = 987610 + i;
                oldCache.put(group, cache.put(group, true));
                var data = new DailyTaskData(); set(data, "id", id); set(data, "type", "DAILY_TASK_SCENE");
                set(data, "finishType", "DAILY_FINISH_MONSTER_NUM"); set(data, "finishProgress", 2); set(data, "taskRewardId", 1);
                set(data, "newGroupVec", List.of(group)); oldData.put(id, GameData.getDailyTaskDataMap().put(id, data));
                var task = new DailyTask(); set(task, "taskId", id); set(task, "finishProgress", 2); set(task, "rewardId", 987650); tasks.add(task);
            }
            monster(scene, 999999).onDeath(1234); // Unrelated group: no task advances.
            assertTrue(tasks.stream().allMatch(t -> t.getProgress() == 0));
            for (int i = 0; i < 4; i++) {
                var first = monster(scene, 987610 + i);
                first.onDeath(1234); first.onDeath(1234);
                assertEquals(1, tasks.get(i).getProgress(), "Repeated death must not impersonate a second wave");
                monster(scene, 987610 + i).onDeath(1234);
                assertTrue(tasks.get(i).isFinished());
            }
            scheduler.runTasks(); scheduler.runTasks();
            assertEquals(4, Collections.frequency(inventory.reasons, ActionReason.DailyTaskHost));
            assertEquals(1, Collections.frequency(inventory.reasons, ActionReason.DailyTaskScore));
            assertTrue(daily.isScoreRewardTaken());
            assertFalse(daily.claimScoreReward());
            monster(scene, 987610).onDeath(1234);
            assertEquals(5, inventory.reasons.size());
        } finally {
            serverField.set(null, originalServer);
            oldCache.forEach((id, value) -> { if (value == null) cache.remove(id); else cache.put(id, value); });
            oldData.forEach((id, value) -> { if (value == null) GameData.getDailyTaskDataMap().remove(id); else GameData.getDailyTaskDataMap().put(id, value); });
            oldRewards.forEach((id, value) -> { if (value == null) GameData.getRewardPreviewDataMap().remove(id); else GameData.getRewardPreviewDataMap().put(id, value); });
        }
    }
    static EntityMonster monster(Scene scene, int group) {
        var monster = allocate(EntityMonster.class); set(monster, "scene", scene); set(monster, "groupId", group);
        set(monster, "position", new Position()); set(monster, "rotation", new Position()); set(monster, "summonTagMap", new HashMap<>());
        var data = new MonsterData(); set(data, "type", MonsterType.values()[0]); set(monster, "monsterData", data); return monster;
    }
    public static class RecordedDaily extends DailyTaskManager {
        @Override public int getScoreRewardId() { return 987651; }
        @Override public void save() {}
        @Override public void syncAll() {}
    }
    public static class RewardPlayer extends Player {
        @Override public boolean isOnline() { return false; }
        @Override public void save() {}
    }
    public static class RewardInventory extends Inventory {
        public RewardInventory() { super(null); }
        List<ActionReason> reasons;
        @Override public void addItemParamDatas(Collection<ItemParamData> items, ActionReason reason) {
            assertEquals(1, items.size()); reasons.add(reason);
        }
    }
}
