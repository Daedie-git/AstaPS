package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.config.Configuration;
import emu.grasscutter.game.world.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.scripts.*;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.scheduler.ServerTaskScheduler;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

public class ScriptLifecycleAuditTest {
    @Test void fractionalAndNamedWildcardTimersAreOneShotSeconds() throws Exception {
        Field field = Grasscutter.class.getDeclaredField("gameServer");
        field.setAccessible(true);
        Object original = field.get(null);
        int originalRate = Configuration.GAME_INFO.tickRateMs;
        try {
            for (int rate : new int[]{200, 1000}) {
                Configuration.GAME_INFO.tickRateMs = rate;
                var scheduler = new ServerTaskScheduler();
                var server = allocate(GameServer.class);
                set(server, "scheduler", scheduler);
                field.set(null, server);
                var scripts = scripts();
                var scene = allocate(Scene.class);
                set(scene, "scheduler", scheduler);
                set(scripts, "scene", scene);
                for (double seconds : new double[]{0.5, 1, 10}) {
                    scripts.events.clear();
                    assertEquals(0, scripts.createGroupTimerEvent(1, "fire_row", seconds));
                    int ticks = (int)Math.ceil(seconds * 1000 / rate);
                    for (int i = 1; i < ticks; i++) scheduler.runTasks();
                    assertTrue(scripts.events.isEmpty(), "Timer must not fire early");
                    scheduler.runTasks();
                    assertEquals(1, scripts.events.size(), "Wildcard listener must receive named event");
                    ScriptArgs args = scripts.events.get(0);
                    assertEquals("fire_row", args.getEventSource());
                    assertEquals("fire_row", ScriptArgs.class.getField("source_name").get(args));
                    for (int i = 0; i < ticks + 2; i++) scheduler.runTasks();
                    assertEquals(1, scripts.events.size(), "Slow callback must not repeat event");
                }
                scripts.createGroupTimerEvent(1, "cancelled", 1);
                scripts.cancelGroupTimerEvent(1, "cancelled");
                for (int i = 0; i < 10; i++) scheduler.runTasks();
                assertEquals(1, scripts.events.size());
                scripts.events.clear();
                scripts.createGroupTimerEvent(1, "replace", 10);
                scripts.createGroupTimerEvent(1, "replace", 0.5);
                for (int i = 0; i < (int)Math.ceil(10_000d / rate) + 2; i++) scheduler.runTasks();
                assertEquals(1, scripts.events.size(), "Replacement must not leave a stale repeating task");
                assertEquals(1, scripts.createGroupTimerEvent(1, "bad", -1));
                assertEquals(1, scripts.createGroupTimerEvent(1, "bad", Double.NaN));
                assertEquals(1, scripts.createGroupTimerEvent(1, "bad", Double.POSITIVE_INFINITY));
            }
        } finally { field.set(null, original); Configuration.GAME_INFO.tickRateMs = originalRate; }
    }

    @Test void unrelatedServerTicksCannotAdvancePausedSceneTimers() throws Exception {
        var field = Grasscutter.class.getDeclaredField("gameServer"); field.setAccessible(true);
        var previous = field.get(null);
        var global = new ServerTaskScheduler();
        var local = new ServerTaskScheduler();
        var server = allocate(GameServer.class); set(server, "scheduler", global);
        var scene = allocate(Scene.class); set(scene, "scheduler", local);
        var scripts = scripts(); set(scripts, "scene", scene);
        try {
            field.set(null, server);
            assertEquals(0, scripts.createGroupTimerEvent(1, "paused", 1));
            for (int i = 0; i < 100; i++) global.runTasks();
            assertTrue(scripts.events.isEmpty(), "Paused scene timers must not follow the global scheduler");
            for (int i = 0; i < 100; i++) local.runTasks();
            assertEquals(1, scripts.events.size());
        } finally { field.set(null, previous); }
    }

    @Test void destructionSavesCachedProgressBeforeClearingIt() {
        var scripts = scripts();
        var scene = allocate(SavingScene.class);
        set(scripts, "scene", scene);
        scene.scripts = scripts;
        scripts.getCachedGroupInstances().put(1, new SceneGroupInstance(new SceneGroup(), allocate(Player.class)));
        scripts.onDestroy();
        assertEquals(1, scene.savedGroups);
        assertTrue(scripts.getCachedGroupInstances().isEmpty());
        scripts.onDestroy();
        assertEquals(1, scene.savedGroups);
    }

    @Test void destructionWaitsNonBlockingForExecutingLuaBeforeFinalSaveAndClear() throws Exception {
        var field = ScriptLoader.class.getDeclaredField("scriptLib"); field.setAccessible(true);
        var original = field.get(null); field.set(null, new ScriptLib());
        var scripts = scripts(BlockedScripts.class);
        scripts.events = null;
        scripts.entered = new CountDownLatch(1); scripts.release = new CountDownLatch(1);
        var scene = allocate(SavingScene.class); scene.scripts = scripts; set(scripts, "scene", scene);
        scripts.instance = new SceneGroupInstance(new SceneGroup(), allocate(Player.class));
        scripts.instance.getCachedVariables().put("late", 1);
        scripts.getCachedGroupInstances().put(1, scripts.instance);
        var future = scripts.callEvent(new ScriptArgs(1, EventType.EVENT_GROUP_LOAD));
        try {
            assertTrue(scripts.entered.await(3, TimeUnit.SECONDS));
            scripts.onDestroy(); // Must not wait while a scene departure holds its monitor.
        } finally {
            scripts.release.countDown(); future.get(3, TimeUnit.SECONDS); field.set(null, original);
        }
        assertEquals(2, scene.savedValue, "Final executing Lua mutation must be included before clearing");
        assertTrue(scripts.getCachedGroupInstances().isEmpty());
    }

    @Test void departureBoundaryKeepsWriteAdmissionOpenUntilFinalSave() throws Exception {
        var field = ScriptLoader.class.getDeclaredField("scriptLib"); field.setAccessible(true);
        var original = field.get(null); field.set(null, new ScriptLib());
        var scripts = scripts(BlockedScripts.class); scripts.events = null;
        scripts.entered = new CountDownLatch(1); scripts.release = new CountDownLatch(1);
        var scene = allocate(FencedSavingScene.class); scene.scripts = scripts; set(scripts, "scene", scene);
        set(scene, "accepted", new CopyOnWriteArrayList<Integer>());
        var player = allocate(SameSceneTransferAuditTest.QuietPlayer.class);
        scripts.instance = new SceneGroupInstance(new SceneGroup(), player);
        scripts.instance.getCachedVariables().put("late", 1);
        scripts.getCachedGroupInstances().put(1, scripts.instance);
        var team = allocate(emu.grasscutter.game.player.TeamManager.class);
        set(team, "avatars", new ArrayList<>()); set(team, "gadgets", Set.of()); set(player, "teamManager", team);
        var world = allocate(World.class); set(scene, "world", world);
        var scenes = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<Scene>();
        scenes.put(3, scene); set(world, "scenes", scenes);
        set(scene, "players", new ArrayList<>(List.of(player))); set(scene, "entities", new HashMap<>());
        set(scene, "scriptManager", scripts); set(player, "scene", scene);
        var gate = Class.forName("emu.grasscutter.game.world.ScenePlayerEntryGate").getDeclaredConstructor();
        gate.setAccessible(true); set(scene, "playerEntryCallbacks", gate.newInstance());
        var event = scripts.callEvent(new ScriptArgs(1, EventType.EVENT_GROUP_LOAD));
        try {
            assertTrue(scripts.entered.await(3, TimeUnit.SECONDS));
            scene.saveGroups(); // Earlier writes can already be flushed while Lua is blocked.
            CompletableFuture<Void> departure;
            synchronized (scene) {
                departure = scene.removePlayerWithCompletion(player);
                assertFalse(departure.isDone(), "Removal must return while Lua is still blocked");
            }
            assertSame(departure, player.getScriptDepartureCompletion());
            var retirement = departure.thenRun(() -> scene.retired = true);
            assertFalse(retirement.isDone(), "Retirement must wait for the final script save submission");
            scripts.release.countDown();
            retirement.get(3, TimeUnit.SECONDS);
            assertEquals(2, scene.accepted.getLast(), "Fake writer must accept the late snapshot before retirement");
            assertEquals(1, scene.accepted.stream().filter(value -> value == 2).count());
            assertTrue(scene.retired);
        } finally {
            scripts.release.countDown(); event.get(3, TimeUnit.SECONDS); field.set(null, original);
        }
    }

    @Test void boundedShutdownJoinsFinalSaveCallbackAndClosesAdmission() throws Exception {
        var field = ScriptLoader.class.getDeclaredField("scriptLib"); field.setAccessible(true);
        var original = field.get(null); field.set(null, new ScriptLib());
        var scripts = scripts(BlockedScripts.class); scripts.events = null;
        scripts.entered = new CountDownLatch(1); scripts.release = new CountDownLatch(1);
        scripts.instance = new SceneGroupInstance(new SceneGroup(), allocate(Player.class));
        scripts.instance.getCachedVariables().put("late", 1);
        scripts.getCachedGroupInstances().put(1, scripts.instance);
        var scene = allocate(BlockingFinalScene.class); scene.scripts = scripts; set(scripts, "scene", scene);
        scene.saveEntered = new CountDownLatch(1); scene.saveRelease = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var dispatch = SceneScriptManager.class.getDeclaredMethod("realCallEvent", ScriptArgs.class);
        dispatch.setAccessible(true);
        var shutdown = SceneScriptManager.class.getDeclaredMethod(
                "awaitEventExecutor", ExecutorService.class, long.class, TimeUnit.class);
        shutdown.setAccessible(true);
        var event = executor.submit(() -> {
            try { dispatch.invoke(scripts, new ScriptArgs(1, EventType.EVENT_GROUP_LOAD)); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        try {
            assertTrue(scripts.entered.await(3, TimeUnit.SECONDS));
            var completion = scripts.onDestroyAsync();
            scripts.release.countDown();
            assertTrue(scene.saveEntered.await(3, TimeUnit.SECONDS));
            assertFalse(completion.isDone(), "Final save callback is still producing");
            assertTimeoutPreemptively(java.time.Duration.ofMillis(300),
                    () -> assertSame(completion, scripts.onDestroyAsync()),
                    "Departure must not wait on a final callback under the manager monitor");
            var timeout = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> shutdown.invoke(null, executor, 30L, TimeUnit.MILLISECONDS));
            assertInstanceOf(TimeoutException.class, timeout.getCause());
            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> {}));
            assertFalse(completion.isDone(), "Timeout must not acknowledge or cancel the producer");
            scene.saveRelease.countDown();
            event.get(3, TimeUnit.SECONDS);
            shutdown.invoke(null, executor, 3L, TimeUnit.SECONDS);
            completion.get(3, TimeUnit.SECONDS);
            assertEquals(2, scene.savedValue);
            assertTrue(scripts.getCachedGroupInstances().isEmpty());
        } finally {
            scripts.release.countDown(); scene.saveRelease.countDown();
            executor.shutdown(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            field.set(null, original);
        }
    }

    public static class BlockingFinalScene extends SavingScene {
        public BlockingFinalScene() { super(); }
        CountDownLatch saveEntered, saveRelease;
        @Override public void saveGroups() {
            saveEntered.countDown();
            try { assertTrue(saveRelease.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            super.saveGroups();
        }
    }

    public static class FencedSavingScene extends SavingScene {
        public FencedSavingScene() { super(); }
        volatile boolean retired;
        List<Integer> accepted;
        @Override public int getId() { return 3; }
        @Override public void saveGroups() {
            if (!retired) scripts.getCachedGroupInstances().values().forEach(group ->
                    accepted.add(group.getCachedVariables().getOrDefault("late", 0)));
        }
    }

    static TimerScripts scripts() { return scripts(TimerScripts.class); }
    static <T extends TimerScripts> T scripts(Class<T> type) {
        var scripts = allocate(type);
        scripts.events = new ArrayList<>();
        scripts.group = new SceneGroup();
        scripts.group.id = 1;
        scripts.group.triggers = new HashMap<>();
        var trigger = new SceneTrigger();
        trigger.setEvent(EventType.EVENT_TIMER_EVENT);
        trigger.setSource("");
        scripts.group.triggers.put("wildcard", trigger);
        try {
            var constructor = Class.forName("emu.grasscutter.scripts.SceneScriptInitGate").getDeclaredConstructor();
            constructor.setAccessible(true);
            set(scripts, "initialization", constructor.newInstance());
        } catch (Exception e) { throw new AssertionError(e); }
        set(scripts, "sealBattleManager", new SealBattleManager(scripts));
        for (String name : new String[]{"activeGroupTimers", "pendingCutsceneGroups", "timeAxis", "currentTriggers", "triggersByGroupScene", "triggerInvocations", "regions", "sceneGroups", "sceneGroupsInstances", "cachedSceneGroupsInstances", "loadedGroupSetPerBlock", "variables"}) set(scripts, name, new HashMap<>());
        set(scripts, "ongoingTriggers", new HashSet<>());
        return scripts;
    }
    public static class TimerScripts extends SceneScriptManager {
        public TimerScripts() { super(null); }
        SceneGroup group;
        List<ScriptArgs> events;
        @Override public SceneGroup getGroupById(int id) { return group; }
        @Override public Future<?> callEvent(ScriptArgs args) {
            if (events == null) return super.callEvent(args);
            events.add(args); return new CompletableFuture<>();
        }
    }
    public static class BlockedScripts extends TimerScripts {
        CountDownLatch entered, release;
        SceneGroupInstance instance;
        @Override public Set<SceneTrigger> getTriggersByEvent(int type) {
            entered.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            instance.getCachedVariables().put("late", 2);
            return Set.of();
        }
    }
    public static class SavingScene extends Scene {
        public SavingScene() { super(null, null); }
        SceneScriptManager scripts;
        int savedGroups, savedValue;
        @Override public void saveGroups() {
            savedGroups += scripts.getCachedGroupInstances().size();
            scripts.getCachedGroupInstances().values().forEach(group -> savedValue = group.getCachedVariables().getOrDefault("late", 0));
        }
    }
}
