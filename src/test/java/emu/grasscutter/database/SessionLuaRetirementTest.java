package emu.grasscutter.database;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import dev.morphia.Datastore;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.*;
import emu.grasscutter.scripts.*;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.*;
import emu.grasscutter.server.game.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import javax.script.SimpleBindings;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

class SessionLuaRetirementTest {
    public static class Scripts extends SceneScriptManager {
        Set<SceneTrigger> triggers;
        public Scripts() { super(null); }
        @Override public Set<SceneTrigger> getTriggersByEvent(int type) { return triggers; }
    }
    public static class Departure extends Player {
        Scene departure;
        @Override public void onLogout() { departure.removePlayer(this); }
    }
    public static class FixtureWorld extends World {
        public FixtureWorld() { super(null); }
        @Override public void deregisterScene(Scene scene) {}
    }
    static Object gate(String className) throws Exception {
        var constructor = Class.forName(className).getDeclaredConstructor(); constructor.setAccessible(true);
        return constructor.newInstance();
    }
    @Test void actualLuaFinalMutationIsAdmittedAndAcknowledgedBeforeRealSessionRetires() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var finalWrite = new CountDownLatch(1); var acknowledge = new CountDownLatch(1);
        var session = new GameSession(null); var next = new GameSession(null);
        String account = "actual-lua-session-retirement";
        var player = new Departure(); player.setUid(909101); player.setMainCharacterId(10000007);
        set(player, "teamManager", new emu.grasscutter.game.player.TeamManager(player));
        var scene = allocate(Scene.class); player.departure = scene;
        set(scene, "world", allocate(FixtureWorld.class));
        set(scene, "players", new CopyOnWriteArrayList<>(List.of(player)));
        set(scene, "entities", new HashMap<>());
        set(scene, "playerEntryCallbacks", gate("emu.grasscutter.game.world.ScenePlayerEntryGate"));
        var scripts = allocate(Scripts.class); set(scripts, "scene", scene); set(scene, "scriptManager", scripts);
        set(scripts, "initialization", gate("emu.grasscutter.scripts.SceneScriptInitGate"));
        set(scripts, "sealBattleManager", new SealBattleManager(scripts));
        for (String name : new String[]{"activeGroupTimers", "pendingCutsceneGroups", "timeAxis", "currentTriggers", "triggersByGroupScene", "triggerInvocations", "regions", "sceneGroups", "sceneGroupsInstances", "cachedSceneGroupsInstances", "loadedGroupSetPerBlock", "variables"}) set(scripts, name, new HashMap<>());
        set(scripts, "ongoingTriggers", new HashSet<>());
        var group = SceneGroup.of(701);
        var globals = JsePlatform.standardGlobals();
        globals.set("block", new ZeroArgFunction() {
            @Override public LuaValue call() {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
                return LuaValue.NIL;
            }
        });
        globals.load("function condition() return true end; function action() block(); lateMutation(); return 0 end").call();
        var bindings = new SimpleBindings(); bindings.put("condition", globals.get("condition")); bindings.put("action", globals.get("action")); group.setBindings(bindings);
        var trigger = new SceneTrigger(); trigger.setName("late"); trigger.setEvent(EventType.EVENT_GROUP_LOAD); trigger.setSource(""); trigger.setCondition("condition"); trigger.setAction("action"); trigger.setCurrentGroup(group); scripts.triggers = Set.of(trigger);
        var datastoreField = DatabaseManager.class.getDeclaredField("gameDatastore"); datastoreField.setAccessible(true); var originalStore = datastoreField.get(null);
        var libField = ScriptLoader.class.getDeclaredField("scriptLib"); libField.setAccessible(true); var originalLib = libField.get(null); libField.set(null, new ScriptLib());
        var snapshots = new CopyOnWriteArrayList<Integer>();
        datastoreField.set(null, Proxy.newProxyInstance(Datastore.class.getClassLoader(), new Class<?>[]{Datastore.class}, (proxy, method, args) -> {
            if (!method.getName().equals("save")) throw new AssertionError("Unexpected live database method: " + method.getName());
            var instance = (SceneGroupInstance) args[0]; int value = instance.getCachedVariables().get("late");
            snapshots.add(value);
            if (value == 2) { finalWrite.countDown(); assertTrue(acknowledge.await(5, TimeUnit.SECONDS)); }
            return instance;
        }));
        Future<?> event = null;
        try {
            GameServer.prepareLoginAdmission(); assertTrue(session.claimAccountLease(account)); session.setPlayer(player); session.markLeaseLoaded(); session.markComponentsReady();
            var instance = new SceneGroupInstance(group, player); instance.getCachedVariables().put("late", 1); scripts.getCachedGroupInstances().put(group.id, instance);
            globals.set("lateMutation", new ZeroArgFunction() { @Override public LuaValue call() { instance.getCachedVariables().put("late", 2); return LuaValue.NIL; } });
            event = scripts.callEvent(new ScriptArgs(group.id, EventType.EVENT_GROUP_LOAD)); assertTrue(entered.await(5, TimeUnit.SECONDS));
            session.close(); session.handleClose();
            var retirement = session.retireAccountLease();
            DatabaseHelper.flushForLoginHandoff().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1), snapshots);
            assertFalse(retirement.isDone()); assertTrue(session.canSubmitSessionWrite()); assertFalse(session.acceptsGameWork()); assertFalse(next.claimAccountLease(account));
            release.countDown(); assertTrue(finalWrite.await(5, TimeUnit.SECONDS));
            assertFalse(retirement.isDone()); assertTrue(session.ownsAccountLease());
            acknowledge.countDown(); retirement.get(5, TimeUnit.SECONDS); event.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2), snapshots); assertTrue(next.claimAccountLease(account));
        } finally {
            release.countDown(); acknowledge.countDown(); if (event != null) event.get(5, TimeUnit.SECONDS);
            try (var ignored = DatabaseWriteBarrier.acquire()) { datastoreField.set(null, originalStore); }
            libField.set(null, originalLib); AccountSessionLease.release(AccountSessionLease.current(account));
        }
    }
}
