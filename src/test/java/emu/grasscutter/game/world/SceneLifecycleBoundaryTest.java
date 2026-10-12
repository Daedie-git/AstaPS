package emu.grasscutter.game.world;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.entity.EntityRegion;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.managers.blossom.BlossomManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.SealBattleManager;
import emu.grasscutter.scripts.constants.ScriptRegionShape;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.server.scheduler.ServerTaskScheduler;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
final class SceneLifecycleBoundaryTest {
    record Saved(boolean cached, Map<String, Integer> variables, Map<Integer, Integer> gadgets) {}

    @Test
    void realUnregisterPersistsAndReloadReusesProgressAcrossRepeatedUnload() throws Exception {
        var scene = scene(3);
        var manager = scene.getScriptManager();
        var group = SceneGroup.of(123);
        group.setLoaded(true); // Installed script metadata; no filesystem/Lua loading in this fixture.
        var variable = new SceneVar();
        variable.name = "progress";
        variable.value = 1;
        group.variables = List.of(variable);
        var gadget = new SceneGadget();
        gadget.config_id = 42;
        gadget.persistent = true;
        gadget.state = 0;
        var saves = new ArrayList<Saved>();
        var instance = new SceneGroupInstance(group, allocate(Player.class), value ->
                saves.add(new Saved(value.isCached(), Map.copyOf(value.getCachedVariables()),
                        Map.copyOf(value.getCachedGadgetStates()))));
        instance.getCachedVariables().put("progress", 7);
        instance.cacheGadgetState(gadget, 201);
        manager.getCachedGroupInstances().put(123, instance);
        manager.loadGroupFromScript(group);
        assertSame(instance, manager.getGroupInstanceById(123));
        assertFalse(instance.isCached());
        assertEquals(1, saves.size());

        var trigger = new SceneTrigger();
        trigger.setName("progress_trigger");
        trigger.setEvent(1);
        trigger.currentGroup = group;
        group.triggers = Map.of(trigger.getName(), trigger);
        manager.registerTrigger(trigger);
        var region = region(group, 50);
        var entityRegion = regionEntity(scene, region, 50);
        group.regions = Map.of(50, region);
        regions(manager).put(50, entityRegion);
        scene.getLoadedGroups().add(group);
        manager.getLoadedGroupSetPerBlock().put(8, new HashSet<>(Set.of(group)));

        scene.unloadGroup(null, 123);
        assertNull(manager.getGroupInstanceById(123));
        assertSame(instance, manager.getCachedGroupInstances().get(123));
        assertTrue(instance.isCached());
        assertTrue(manager.getTriggersByEvent(1).isEmpty());
        assertTrue(regions(manager).isEmpty());
        assertTrue(scene.getLoadedGroups().isEmpty());
        assertTrue(manager.getLoadedGroupSetPerBlock().isEmpty());
        assertEquals(2, saves.size());
        assertEquals(new Saved(true, Map.of("progress", 7), Map.of(42, 201)), saves.getLast());
        int saveCount = saves.size();
        scene.unloadGroup(null, 123);
        assertEquals(saveCount, saves.size(), "Absent group must not resubmit a save");
        assertNull(manager.getGroupInstanceById(123));

        manager.loadGroupFromScript(group);
        assertSame(instance, manager.getGroupInstanceById(123));
        assertSame(instance, manager.getCachedGroupInstances().get(123));
        assertFalse(instance.isCached());
        assertEquals(7, manager.getVariables(123).get("progress"));
        assertEquals(201, instance.getCachedGadgetState(gadget));
        assertEquals(new Saved(false, Map.of("progress", 7), Map.of(42, 201)), saves.getLast());
        // Exercise active-instance discovery without either loaded set on the second departure.
        scene.unloadGroup(null, 123);
        assertNull(manager.getGroupInstanceById(123));
        assertTrue(instance.isCached());
        assertEquals(saveCount + 2, saves.size());
        scene.unloadGroup(null, 123);
        assertEquals(saveCount + 2, saves.size());
    }

    @Test
    void partialRegionStageFailureAbortsLoadingAndRespawnButSiblingCompletes() throws Exception {
        var world = allocate(World.class);
        set(world, World.class, "players", new ArrayList<>(List.of(allocate(Player.class))));
        set(world, World.class, "scenes", Int2ObjectMaps.synchronize(new Int2ObjectLinkedOpenHashMap<Scene>()));
        set(world, World.class, "entity", allocate(emu.grasscutter.game.entity.EntityWorld.class));
        set(world, World.class, "tickCount", 1);
        set(world, World.class, "timeLocked", true);
        var failing = scene(3);
        var healthy = scene(4);
        set(failing, Scene.class, "world", world);
        set(healthy, Scene.class, "world", world);
        var group = SceneGroup.of(123);
        var first = region(group, 50);
        var admitted = regionEntity(failing, first, 50);
        var malformed = region(group, 51);
        malformed.pos = null; // Actual contains() fails after the first region applies membership.
        regions(failing.getScriptManager()).put(50, admitted);
        regions(failing.getScriptManager()).put(51, regionEntity(failing, malformed, 51));
        var subject = regionEntity(failing, first, 100);
        failing.getEntities().put(100, subject);
        var completions = new ArrayList<Integer>();
        failing.runWhenFinished(() -> completions.add(3));
        healthy.runWhenFinished(() -> completions.add(4));
        world.registerScene(failing);
        world.registerScene(healthy);

        var failure = assertThrows(NullPointerException.class, failing::onTick);
        assertEquals(SceneRegion.class.getName(), failure.getStackTrace()[0].getClassName());
        assertEquals("contains", failure.getStackTrace()[0].getMethodName());
        assertEquals(Set.of(100), admitted.getEntities());
        admitted.getEntities().clear();
        assertDoesNotThrow(world::onTick);
        assertEquals(Set.of(100), admitted.getEntities(), "First region must really mutate before failure");
        assertFalse(failing.isFinishedLoading());
        assertEquals(0, failing.respawnChecks);
        assertTrue(healthy.isFinishedLoading());
        assertEquals(1, healthy.respawnChecks);
        assertEquals(List.of(4), completions);

        regions(failing.getScriptManager()).remove(51);
        assertDoesNotThrow(world::onTick);
        assertTrue(failing.isFinishedLoading());
        assertEquals(1, failing.respawnChecks);
        assertEquals(2, healthy.respawnChecks);
        assertEquals(List.of(4, 3), completions);
    }

    private static SceneRegion region(SceneGroup group, int id) {
        var region = new SceneRegion();
        region.group = group;
        region.config_id = id;
        region.shape = ScriptRegionShape.SPHERE;
        region.pos = new Position();
        region.radius = 5;
        return region;
    }

    private static EntityRegion regionEntity(Scene scene, SceneRegion meta, int id) throws Exception {
        var region = allocate(EntityRegion.class);
        region.setId(id);
        set(region, EntityRegion.class, "metaRegion", meta);
        set(region, EntityRegion.class, "entities", new HashSet<Integer>());
        set(region, EntityRegion.class, "position", new Position());
        return region;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, EntityRegion> regions(SceneScriptManager manager) throws Exception {
        var field = SceneScriptManager.class.getDeclaredField("regions");
        field.setAccessible(true);
        return (Map<Integer, EntityRegion>) field.get(manager);
    }

    private static BoundaryScene scene(int id) throws Exception {
        var scene = allocate(BoundaryScene.class);
        scene.id = id;
        set(scene, Scene.class, "entities", new LinkedHashMap<Integer, GameEntity>());
        set(scene, Scene.class, "players", new ArrayList<Player>());
        set(scene, Scene.class, "loadedGroups", new HashSet<SceneGroup>());
        set(scene, Scene.class, "loadedGridBlocks", new HashSet<>());
        set(scene, Scene.class, "npcBornEntrySet", new HashSet<>());
        set(scene, Scene.class, "scheduler", new ServerTaskScheduler());
        set(scene, Scene.class, "blossomManager", new BlossomManager(scene));
        set(scene, Scene.class, "afterLoadedCallbacks", new SceneLoadCallbackGate());
        var manager = allocate(EventBoundaryManager.class);
        set(manager, SceneScriptManager.class, "scene", scene);
        for (var name : List.of("sceneGroups", "sceneGroupsInstances", "cachedSceneGroupsInstances",
                "orphanGroupVariables", "loadedGroupSetPerBlock", "currentTriggers", "triggerInvocations")) {
            set(manager, SceneScriptManager.class, name, new HashMap<>());
        }
        set(manager, SceneScriptManager.class, "regions", new LinkedHashMap<Integer, EntityRegion>());
        set(manager, SceneScriptManager.class, "sealBattleManager", new SealBattleManager(manager));
        set(scene, Scene.class, "scriptManager", manager);
        return scene;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class EventBoundaryManager extends SceneScriptManager {
        EventBoundaryManager() { super(null); }
        // Lua dispatch only; real region processing remains.
        @Override public java.util.concurrent.Future<?> callEvent(ScriptArgs args) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    private static final class BoundaryScene extends Scene {
        int id;
        int respawnChecks;
        BoundaryScene() { super(null, null); }
        @Override public int getId() { return id; }
        @Override public int getPlayerCount() { return 1; }
        @Override public int getSceneTime() { return 1000; }
        @Override public SceneType getSceneType() { return SceneType.SCENE_WORLD; }
        @Override public void broadcastPacket(BasePacket packet) {}
        @Override protected void checkPlayerRespawn() { respawnChecks++; }
        @Override public void saveGroups() {}
    }
}
