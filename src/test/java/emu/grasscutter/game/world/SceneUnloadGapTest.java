package emu.grasscutter.game.world;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.entity.EntityMonster;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.data.*;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
final class SceneUnloadGapTest {
    @Test
    void groupRetirementClearsRuntimeStateBeforeRemovalCallbacksAndPreservesReplacements() throws Exception {
        var scene = scene();
        set(scene, Scene.class, "entities", new LinkedHashMap<Integer, GameEntity>());
        var first = (RecordingMonster) entity(101, 123, 8);
        var stale = (RecordingMonster) entity(102, 123, 8);
        var replacement = entity(102, 124, 8);
        var retained = entity(103, 124, 8);
        var otherBlock = entity(104, 123, 9);
        var stateField = emu.grasscutter.game.entity.EnvironmentalSealHelper.class
                .getDeclaredField("burnLeftByEntityId");
        stateField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var state = (Map<Integer, Float>) stateField.get(null);
        try {
            for (int id : List.of(101, 102, 103, 104)) state.put(id, 100f);
            first.removal = () -> {
                assertFalse(state.containsKey(101), "Retirement cleanup precedes callbacks");
                scene.getEntities().put(102, replacement);
                state.put(102, 200f);
            };
            for (var member : List.of(first, stale, retained, otherBlock)) {
                scene.getEntities().put(member.getId(), member);
            }
            var block = new SceneBlock();
            block.id = 8;
            scene.unloadGroup(block, 123);
            assertFalse(state.containsKey(101));
            assertSame(replacement, scene.getEntities().get(102));
            assertEquals(200f, state.get(102));
            assertEquals(100f, state.get(103));
            assertEquals(100f, state.get(104));
            assertEquals(1, first.removals);
            assertEquals(0, stale.removals);
            scene.unloadGroup(block, 123);
            assertEquals(1, first.removals, "Repeated unload does not retire twice");
            assertEquals(200f, state.get(102));
        } finally {
            for (int id : List.of(101, 102, 103, 104)) {
                emu.grasscutter.game.entity.EntityRuntimeStateCleanup.clear(entity(id, 0, 0));
            }
        }
    }

    @Test
    void missingBlockStillRemovesEntitiesAndRegisteredGroup() throws Exception {
        var scene = scene();
        var group = SceneGroup.of(123);
        scene.getLoadedGroups().add(group);
        scene.manager.blocks.put(8, new HashSet<>(Set.of(group)));
        var removed = entity(1, 123, 8);
        var retained = entity(2, 124, 8);
        scene.getEntities().put(1, removed);
        scene.getEntities().put(2, retained);

        assertDoesNotThrow(() -> scene.unloadGroup(null, 123));
        assertEquals(Set.of(2), scene.getEntities().keySet());
        assertTrue(scene.getLoadedGroups().isEmpty());
        assertTrue(scene.manager.blocks.isEmpty());
        assertEquals(List.of(group), scene.manager.unregistered);
    }

    @Test
    void missingLoadedBlockSetDoesNotPreventTeardown() throws Exception {
        var scene = scene();
        var group = SceneGroup.of(123);
        var block = new SceneBlock();
        block.id = 8;
        block.groups = Map.of(123, group);
        scene.getLoadedGroups().add(group);
        scene.getEntities().put(1, entity(1, 123, 8));
        assertDoesNotThrow(() -> scene.unloadGroup(block, 123));
        assertTrue(scene.getEntities().isEmpty());
        assertEquals(List.of(group), scene.manager.unregistered);
        scene.unloadGroup(block, 123);
        assertTrue(scene.getEntities().isEmpty());
    }

    @Test
    void absentBlockMetadataUsesKnownLoadedGroupWithoutLoadingAnother() throws Exception {
        var scene = scene();
        var group = SceneGroup.of(123);
        scene.manager.blocks.put(8, new HashSet<>(Set.of(group)));
        var block = new SceneBlock();
        block.id = 8;
        scene.unloadGroup(block, 123);
        assertEquals(List.of(group), scene.manager.unregistered);
        assertTrue(scene.manager.blocks.isEmpty());
    }

    @Test
    void activeInstanceWithoutLoadedSetsStillDetachesItsRegionsAndTriggers() throws Exception {
        var scene = scene();
        var group = SceneGroup.of(123);
        group.triggers = Map.of("trigger", new SceneTrigger());
        group.regions = Map.of(1, new SceneRegion());
        scene.manager.active = new SceneGroupInstance();
        scene.manager.active.setLuaGroup(group);
        scene.unloadGroup(null, 123);
        assertEquals(List.of(group), scene.manager.unregistered);
        assertEquals(1, scene.manager.triggersRemoved);
        assertEquals(1, scene.manager.regionsRemoved);
    }

    @Test
    void failingEntityDoesNotSkipIndependentEntity() throws Exception {
        var scene = scene();
        var failing = (RecordingMonster) entity(1, 123, 8);
        var healthy = (RecordingMonster) entity(2, 124, 8);
        failing.fail = true;
        scene.getEntities().put(1, failing);
        scene.getEntities().put(2, healthy);
        assertDoesNotThrow(() -> scene.tickEntities(10));
        assertEquals(1, healthy.ticks);
        assertEquals(2, scene.getEntities().size());
    }

    @Test
    void callbackRemovalSkipsStaleSnapshotMemberRegardlessOfIterationOrder() throws Exception {
        var scene = scene();
        var first = (RecordingMonster) entity(1, 123, 8);
        var second = (RecordingMonster) entity(2, 123, 8);
        first.callback = () -> scene.getEntities().remove(2);
        second.callback = () -> scene.getEntities().remove(1);
        scene.getEntities().put(1, first);
        scene.getEntities().put(2, second);
        scene.tickEntities(10);
        assertEquals(1, first.ticks + second.ticks);
        assertEquals(1, scene.getEntities().size());
    }

    @Test
    void callbackReplacementSkipsOldAndNewMemberUntilNextTick() throws Exception {
        var scene = scene();
        var first = (RecordingMonster) entity(1, 123, 8);
        var second = (RecordingMonster) entity(2, 123, 8);
        var replacement = (RecordingMonster) entity(3, 123, 8);
        first.callback = () -> {
            replacement.setId(2);
            scene.getEntities().put(2, replacement);
            first.callback = null;
        };
        second.callback = () -> {
            replacement.setId(1);
            scene.getEntities().put(1, replacement);
            second.callback = null;
        };
        scene.getEntities().put(1, first);
        scene.getEntities().put(2, second);
        scene.tickEntities(10);
        assertEquals(1, first.ticks + second.ticks);
        assertEquals(0, replacement.ticks);
        assertSame(replacement, scene.getEntities().get(replacement.getId()));
        scene.tickEntities(11);
        assertEquals(1, replacement.ticks);
        assertEquals(2, first.ticks + second.ticks);
    }

    @Test
    void deathCheckReplacementIsNotRemovedWithDeadSnapshotEntity() throws Exception {
        var scene = scene();
        var dead = (RecordingMonster) entity(1, 123, 8);
        var replacement = (RecordingMonster) entity(1, 123, 8);
        dead.alive = () -> {
            scene.getEntities().put(1, replacement);
            return false;
        };
        scene.getEntities().put(1, dead);
        scene.tickEntities(10);
        assertSame(replacement, scene.getEntities().get(1));
        assertEquals(0, dead.ticks);
        assertEquals(0, replacement.ticks);
        scene.tickEntities(11);
        assertEquals(1, replacement.ticks);
    }

    private static RecordingScene scene() throws Exception {
        var scene = allocate(RecordingScene.class);
        scene.manager = allocate(RecordingManager.class);
        scene.manager.blocks = new HashMap<>();
        scene.manager.unregistered = new ArrayList<>();
        set(scene, Scene.class, "entities", new HashMap<Integer, GameEntity>());
        set(scene, Scene.class, "loadedGroups", new HashSet<SceneGroup>());
        set(scene, Scene.class, "scriptManager", scene.manager);
        return scene;
    }

    private static GameEntity entity(int id, int group, int block) throws Exception {
        var entity = allocate(RecordingMonster.class);
        entity.setId(id);
        entity.setGroupId(group);
        entity.setBlockId(block);
        return entity;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RecordingScene extends Scene {
        RecordingManager manager;
        RecordingScene() { super(null, null); }
        @Override public int getId() { return 3; }
        @Override public void broadcastPacket(BasePacket packet) {}
    }

    private static final class RecordingMonster extends EntityMonster {
        int ticks;
        boolean fail;
        Runnable callback;
        Runnable removal;
        int removals;
        java.util.function.BooleanSupplier alive;
        RecordingMonster() { super(null, null, null, null, 1); }
        @Override public void onRemoved() {
            removals++;
            if (removal != null) removal.run();
        }
        @Override public boolean isAlive() { return alive == null || alive.getAsBoolean(); }
        @Override public void onTick(int time) {
            if (fail) throw new IllegalStateException("entity failure");
            ticks++;
            if (callback != null) callback.run();
        }
    }

    private static final class RecordingManager extends SceneScriptManager {
        Map<Integer, Set<SceneGroup>> blocks;
        List<SceneGroup> unregistered;
        SceneGroupInstance active;
        int triggersRemoved;
        int regionsRemoved;
        @Override public SceneGroupInstance getGroupInstanceById(int id) { return active; }
        @Override public void deregisterTrigger(SceneTrigger trigger) { triggersRemoved++; }
        @Override public synchronized void deregisterRegion(SceneRegion region) { regionsRemoved++; }
        RecordingManager() { super(null); }
        @Override public Map<Integer, Set<SceneGroup>> getLoadedGroupSetPerBlock() { return blocks; }
        @Override public void unregisterGroup(SceneGroup group) { unregistered.add(group); }
    }
}
