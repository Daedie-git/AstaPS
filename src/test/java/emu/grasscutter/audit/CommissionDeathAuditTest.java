package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.game.dailytask.DailyTaskManager;
import emu.grasscutter.game.dungeons.enums.DungeonPassConditionType;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.MonsterType;
import emu.grasscutter.game.world.*;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.scripts.SceneScriptManager;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommissionDeathAuditTest {
    @Test void realMonsterDeathCreditsHostWorldCommissionExactlyOnce() {
        var owner = allocate(Player.class);
        var daily = allocate(Deaths.class);
        set(owner, "dailyTaskManager", daily);
        var world = allocate(World.class);
        set(world, "host", owner);
        var scene = allocate(CommissionScene.class);
        set(scene, "world", world);
        set(scene, "players", List.of());
        set(scene, "deadSpawnedEntities", new HashSet<>());
        var scripts = allocate(NoGroups.class);
        set(scene, "scriptManager", scripts);
        var monster = allocate(EntityMonster.class);
        set(monster, "scene", scene);
        set(monster, "position", new Position());
        set(monster, "rotation", new Position());
        set(monster, "groupId", 123);
        set(monster, "summonTagMap", new HashMap<>());
        var data = new MonsterData();
        set(data, "type", MonsterType.values()[0]);
        set(monster, "monsterData", data);
        monster.onDeath(987); // Killer may be a guest; commission belongs to this world's host.
        monster.onDeath(987);
        assertEquals(List.of(123), daily.groups);
        assertSame(scene, daily.scene);
    }
    public static class Deaths extends DailyTaskManager {
        List<Integer> groups = new ArrayList<>();
        Scene scene;
        @Override public synchronized void onMonsterDeath(Scene scene, int group) {
            if (groups == null) groups = new ArrayList<>();
            this.scene = scene; groups.add(group);
        }
    }
    public static class CommissionScene extends Scene {
        public CommissionScene() { super(null, null); }
        @Override public int getId() { return 3; }
        @Override public emu.grasscutter.game.entity.GameEntity getEntityById(int id) { return null; }
        @Override public void triggerDungeonEvent(DungeonPassConditionType type, int... args) {}
    }
    public static class NoGroups extends SceneScriptManager {
        public NoGroups() { super(null); }
        @Override public SceneGroupInstance getGroupInstanceById(int group) { return null; }
    }
}
