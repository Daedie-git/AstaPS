package emu.grasscutter.game.ability;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.binout.*;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.game.ability.actions.ActionTriggerAbility;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.*;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.net.packet.BasePacket;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
final class QueuedAbilityLifetimeTest {
    @Test void unhandledGunshotGrantsSwiftHuntBeforeQueueDrain() throws Exception {
        var f = new Fixture();
        var avatar = allocate(emu.grasscutter.game.avatar.Avatar.class);
        set(avatar, emu.grasscutter.game.avatar.Avatar.class, "avatarId", 10000098);
        set(avatar, emu.grasscutter.game.avatar.Avatar.class, "owner", f.owner);
        set(avatar, emu.grasscutter.game.avatar.Avatar.class, "fightProperties", new it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap());
        var entity = allocate(EntityAvatar.class);
        set(entity, EntityAvatar.class, "avatar", avatar);
        set(entity, GameEntity.class, "scene", f.scene);
        set(entity, GameEntity.class, "id", 234);
        set(entity, GameEntity.class, "globalAbilityValues", new java.util.concurrent.ConcurrentHashMap<String, Float>());
        entity.getGlobalAbilityValues().put("_ABILITY_Clorinde_ElementalArt_Active_Flag", 1f);
        entity.setFightProperty(emu.grasscutter.game.props.FightProperty.FIGHT_PROP_MAX_HP, 1000f);
        set(f.ability, Ability.class, "owner", entity);
        f.ability.getData().abilityName = "Avatar_Clorinde_ElementalArt_GunShot";
        f.scene.entities.put(entity.getId(), entity);
        var action = new AbilityModifierAction(); action.type = AbilityModifierAction.Type.ActCameraRadialBlur;
        try {
            f.manager.executeAction(f.ability, action, ByteString.EMPTY, entity);
            assertEquals(350f, entity.getFightProperty(emu.grasscutter.game.props.FightProperty.FIGHT_PROP_CUR_HP_DEBTS));
            assertTrue(f.queue.isEmpty(), "missing generic handler must not delay the fired-shot compatibility hook");
        } finally { ClorindeBoLUtil.clearEntityState(entity.getId()); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {10000096, 10000098})
    void unhandledHpDebtMixinConvertsCurrentOwnerImmediatelyButNotRetiredOwner(int avatarId) throws Exception {
        var f = new Fixture();
        var avatar = allocate(emu.grasscutter.game.avatar.Avatar.class);
        set(avatar, emu.grasscutter.game.avatar.Avatar.class, "avatarId", avatarId);
        set(avatar, emu.grasscutter.game.avatar.Avatar.class, "owner", f.owner);
        var entity = allocate(EntityAvatar.class);
        set(entity, EntityAvatar.class, "avatar", avatar);
        set(entity, GameEntity.class, "scene", f.scene);
        set(entity, GameEntity.class, "id", 235);
        set(f.ability, Ability.class, "owner", entity);
        f.scene.entities.put(entity.getId(), entity);
        var mixin = new AbilityMixinData(); mixin.type = AbilityMixinData.Type.SwitchHealToHPDebtsMixin;
        f.manager.executeMixin(f.ability, mixin, ByteString.EMPTY);
        assertTrue(entity.isConvertToHpDebtRaw()); assertTrue(f.queue.isEmpty());
        entity.setConvertToHpDebt(false);
        f.owner.setWorld(null);
        f.manager.executeMixin(f.ability, mixin, ByteString.EMPTY);
        assertFalse(entity.isConvertToHpDebtRaw());
    }

    @Test void directTriggerRejectsDepartedOwner() throws Exception {
        var f = new Fixture();
        f.owner.setWorld(null);
        var action = new AbilityModifierAction();
        action.abilityName = "RetiredTrigger";
        assertFalse(assertDoesNotThrow(() -> new ActionTriggerAbility().execute(
            f.ability, action, ByteString.EMPTY, f.entity)));
    }

    @Test void queuedTriggerRejectsDepartureWithoutLoggingException() throws Exception {
        var f = new Fixture();
        var action = new AbilityModifierAction();
        action.type = AbilityModifierAction.Type.TriggerAbility;
        action.abilityName = "LifetimeFixture";
        var logger = emu.grasscutter.Grasscutter.getLogger();
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.setContext(logger.getLoggerContext()); events.start(); logger.addAppender(events);
        try {
            f.manager.executeAction(f.ability, action, ByteString.EMPTY, f.entity);
            assertEquals(1, f.queue.size());
            f.owner.setWorld(null); f.drain();
            assertEquals(List.of(), events.list.stream().filter(e -> e.getThrowableProxy() != null)
                .map(e -> e.getThrowableProxy().getClassName() + ": "
                    + e.getThrowableProxy().getMessage()).toList(),
                "Retired TriggerAbility must be rejected, not caught and logged");
            assertEquals(7f, f.entity.values.get("lease_mark"));
        } finally { logger.detachAppender(events); events.stop(); }
    }

    @Test void currentTriggerRunsExistingAbilityStart() throws Exception {
        var f = new Fixture();
        var clear = new AbilityModifierAction();
        clear.type = AbilityModifierAction.Type.ClearGlobalValue; clear.key = "lease_mark";
        f.ability.getData().onAbilityStart = new AbilityModifierAction[] {clear};
        var trigger = new AbilityModifierAction();
        trigger.type = AbilityModifierAction.Type.TriggerAbility; trigger.abilityName = "LifetimeFixture";
        f.manager.executeAction(f.ability, trigger, ByteString.EMPTY, f.entity); f.drain();
        assertFalse(f.entity.values.containsKey("lease_mark"));
        assertEquals(1, f.entity.updates);
        assertEquals(1, f.entity.abilities.size());
    }

    @Test void queuedActionRejectsSceneTransferAndSameIdReplacement() throws Exception {
        var moved = new Fixture(); moved.queueClear();
        moved.owner.setScene(allocate(S.class)); moved.drain();
        assertEquals(7f, moved.entity.values.get("lease_mark"));
        var replaced = new Fixture(); replaced.queueClear();
        replaced.scene.entities.put(replaced.entity.getId(), allocate(E.class)); replaced.drain();
        assertEquals(7f, replaced.entity.values.get("lease_mark"));
    }

    @Test void queuedActionCannotClearDepartedOwnersState() throws Exception {
        var f = new Fixture(); f.queueClear();
        f.owner.setWorld(null);
        f.drain();
        assertEquals(7f, f.entity.values.get("lease_mark"));
    }

    @Test void queuedActionCannotFollowReplacedSession() throws Exception {
        var f = new Fixture(); f.queueClear();
        f.owner.setSession(new GameSession(null));
        f.drain();
        assertEquals(7f, f.entity.values.get("lease_mark"));
    }

    @Test void separateLiveTargetDoesNotReviveRetiredSourceWork() throws Exception {
        for (boolean retire : List.of(false, true)) {
            var f = new Fixture(); var target = allocate(E.class);
            target.scene = f.scene; target.values = new HashMap<>();
            target.values.put("lease_mark", 7f);
            set(target, GameEntity.class, "id", 1);
            f.scene.entities.put(target.getId(), target);
            var action = new AbilityModifierAction();
            action.type = AbilityModifierAction.Type.ClearGlobalValue; action.key = "lease_mark";
            f.manager.executeAction(f.ability, action, ByteString.EMPTY, target);
            if (retire) EntityRuntimeStateCleanup.clear(f.entity);
            f.drain();
            assertEquals(retire, target.values.containsKey("lease_mark"));
            assertEquals(retire ? 0 : 1, target.updates);
        }
    }

    @Test void queuedActionRejectsRemovedRegisteredTarget() throws Exception {
        var f = new Fixture(); f.queueClear();
        f.scene.entities.remove(f.entity.getId());
        f.drain();
        assertEquals(7f, f.entity.values.get("lease_mark"));
    }

    @Test void currentCoopOwnerMayDifferFromDispatchManagerPlayer() throws Exception {
        var f = new Fixture(); f.queueClear(); f.drain();
        assertFalse(f.entity.values.containsKey("lease_mark"));
        assertEquals(1, f.entity.updates);
    }

    @Test void startupWorkDoesNotRequireActiveSessionOrSceneRegistration() throws Exception {
        var f = new Fixture();
        f.scene.entities.clear();
        f.queueClear(); f.drain();
        assertFalse(f.entity.values.containsKey("lease_mark"));
        assertEquals(GameSession.SessionState.WAITING_FOR_TOKEN, f.owner.getSession().getState());
    }

    @Test void queuedUnregisteredEntityWorkRejectsRuntimeStateRetirement() throws Exception {
        var f = new Fixture(); f.scene.entities.clear(); f.queueClear();
        EntityRuntimeStateCleanup.clear(f.entity); f.drain();
        assertEquals(7f, f.entity.values.get("lease_mark"));
    }

    @Test void actualAbilityInitializationRunsBeforeEntityRegistrationAndActiveLogin() throws Exception {
        var f = new Fixture(); f.scene.entities.clear(); f.entity.abilities.clear();
        var clear = new AbilityModifierAction();
        clear.type = AbilityModifierAction.Type.ClearGlobalValue; clear.key = "lease_mark";
        var data = new AbilityData(); data.abilityName = "InitializationFixture";
        data.onAdded = new AbilityModifierAction[] {clear};
        f.manager.addAbilityToEntity(f.entity, data);
        assertEquals(1, f.queue.size()); f.drain();
        assertEquals(1, f.entity.abilities.size());
        assertFalse(f.entity.values.containsKey("lease_mark"));
        assertEquals(1, f.entity.updates);
    }

    @Test void queuedMixinRejectsDepartureAndPreservesCurrentWork() throws Exception {
        var stale = new Fixture(); var current = new Fixture();
        var data = clearMixin();
        stale.manager.executeMixin(stale.ability, data, ByteString.EMPTY);
        current.manager.executeMixin(current.ability, data, ByteString.EMPTY);
        stale.owner.setWorld(null);
        stale.queue.remove().run();
        assertTrue(stale.queue.isEmpty(), "Retired mixin must not dispatch child actions");
        assertEquals(7f, stale.entity.values.get("lease_mark"));
        current.drain();
        assertFalse(current.entity.values.containsKey("lease_mark"));
        assertEquals(1, current.entity.updates);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void dispatchHostSceneMovePreservesGuestActionAndMixin(boolean mixin) throws Exception {
        try (var f = new TeamFixture(true)) {
            if (mixin) {
                f.drain();
                assertFalse(f.team.getGlobalAbilityValues().containsKey("lease_mark"));
                f.team.getGlobalAbilityValues().put("lease_mark", 7f);
                f.base.host.manager.executeMixin(f.team.getInstancedAbilities().get(0), clearMixin(), ByteString.EMPTY);
            }
            f.base.host.setScene(allocate(S.class));
            f.drain();
            assertFalse(f.team.getGlobalAbilityValues().containsKey("lease_mark"));
            assertSame(f.base.scene, f.base.owner.getScene());
            assertSame(f.base.scene, f.team.getScene());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"current", "world", "foreignWorld", "replacement"})
    void realGuestTeamInitializationUsesGuestLifetimeNotHostAbilityOwner(String retirement) throws Exception {
        {
            try (var f = new TeamFixture()) {
                var team = f.team;
                var ability = team.getInstancedAbilities().get(0);
                assertSame(f.base.host, ability.getPlayerOwner(), "Real team initialization uses host manager");
                assertNull(team.getScene());
                assertEquals(1, f.queue.size());
                switch (retirement) {
                    case "world" -> f.base.owner.setWorld(null);
                    case "foreignWorld" -> f.base.owner.setWorld(allocate(W.class));
                    case "replacement" -> f.base.owner.setSession(new GameSession(null));
                }
                f.drain();
                assertEquals(!retirement.equals("current"), team.getGlobalAbilityValues().containsKey("lease_mark"), retirement);
                if (retirement.equals("current")) assertEquals(GameSession.SessionState.WAITING_FOR_TOKEN,
                    f.base.owner.getSession().getState());
            }
        }
    }

    @Test void realSceneEntityInitializationSurvivesUnrelatedHostMove() throws Exception {
        try (var f = new TeamFixture()) {
            f.drain();
            var entity = new EntityScene(f.base.scene);
            entity.getGlobalAbilityValues().put("lease_mark", 7f);
            assertEquals(1, f.queue.size());
            f.base.host.setScene(allocate(S.class));
            f.drain();
            assertFalse(entity.getGlobalAbilityValues().containsKey("lease_mark"));
            assertSame(f.base.scene, entity.getScene());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"current", "foreign", "scene"})
    void actualWorldEntityStartupBindsOnlyItsConstructingWorld(String admission) throws Exception {
        try (var f = new TeamFixture()) {
            f.drain();
            f.base.host.setWorld(null); f.base.host.setScene(null);
            var entity = new EntityWorld(f.base.world);
            entity.getGlobalAbilityValues().put("lease_mark", 7f);
            assertEquals(1, f.queue.size());
            f.base.host.setWorld(admission.equals("foreign") ? allocate(W.class) : f.base.world);
            if (admission.equals("scene")) f.base.host.setScene(f.base.scene);
            f.drain();
            assertEquals(admission.equals("foreign"),
                entity.getGlobalAbilityValues().containsKey("lease_mark"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void captureAndWorkerGuardFailuresAreLoggedForActionAndMixin(boolean worker, boolean mixin) throws Exception {
        {
            var f = new Fixture();
            var queue = new java.util.concurrent.ArrayBlockingQueue<java.util.concurrent.FutureTask<Void>>(32);
            var manager = new AbilityManager(f.host,
                task -> queue.add(new java.util.concurrent.FutureTask<Void>(task, null)));
            var logger = emu.grasscutter.Grasscutter.getLogger();
            var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
            events.setContext(logger.getLoggerContext()); events.start(); logger.addAppender(events);
            try {
                if (!worker) f.scene.lookupFailure = new IllegalStateException("capture-guard-fixture");
                assertDoesNotThrow(() -> {
                    if (mixin) manager.executeMixin(f.ability, clearMixin(), ByteString.EMPTY);
                    else manager.executeAction(f.ability, clearAction(), ByteString.EMPTY, f.entity);
                });
                if (worker) {
                    assertEquals(1, queue.size());
                    f.scene.lookupFailure = new IllegalStateException("worker-guard-fixture");
                    // submit-style FutureTask intentionally has no get(): logging must be explicit.
                    queue.remove().run();
                }
                assertTrue(queue.isEmpty());
                assertEquals(1, events.list.stream().filter(e -> e.getThrowableProxy() != null
                    && e.getThrowableProxy().getMessage().equals(worker ? "worker-guard-fixture" : "capture-guard-fixture")).count());
                assertEquals(7f, f.entity.values.get("lease_mark"));
            } finally { logger.detachAppender(events); events.stop(); }
        }
    }

    @Test void constructorQueuedMonsterRejectsFinalPlayerSceneDestruction() throws Exception {
        try (var f = new MonsterSceneFixture(false)) {
            var worldEntity = new EntityWorld(f.base.world);
            worldEntity.getGlobalAbilityValues().put("lease_mark", 7f);
            assertEquals(2, f.queue.size());
            f.departHost();
            assertTrue(f.scene.getScriptManager().isDestroyed());
            assertFalse(f.base.world.getScenes().containsKey(f.scene.getId()));
            assertSame(f.monster, f.scene.getEntityById(f.monster.getId()),
                "Scene destruction retains the old monster without retiring its epoch");
            f.drain();
            assertEquals(7f, f.monster.getGlobalAbilityValues().get("lease_mark"));
            assertFalse(worldEntity.getGlobalAbilityValues().containsKey("lease_mark"),
                "World-scoped work ignores destruction of the host's unrelated scene");
        }
    }

    @Test void constructorQueuedMonsterRunsWhenGuestKeepsSourceSceneAlive() throws Exception {
        try (var f = new MonsterSceneFixture(true)) {
            f.departHost();
            assertFalse(f.scene.getScriptManager().isDestroyed());
            assertSame(f.scene, f.base.world.getScenes().get(f.scene.getId()));
            assertEquals(List.of(f.base.owner), f.scene.getPlayers());
            f.drain();
            assertFalse(f.monster.getGlobalAbilityValues().containsKey("lease_mark"));
        }
    }

    @Test void constructorQueuedMonsterRunsInCurrentScene() throws Exception {
        try (var f = new MonsterSceneFixture(false)) {
            assertFalse(f.scene.getScriptManager().isDestroyed());
            assertSame(f.scene, f.base.world.getScenes().get(f.scene.getId()));
            f.drain();
            assertFalse(f.monster.getGlobalAbilityValues().containsKey("lease_mark"));
        }
    }

    /** Real monster constructor, script manager, removePlayer and world registry; no Lua or DB. */
    static final class MonsterSceneFixture implements AutoCloseable {
        static final String ABILITY = "MonsterSceneLifetimeFixture";
        static final int AFFIX = -910001;
        final Fixture base = new Fixture();
        final java.util.concurrent.ArrayBlockingQueue<java.util.concurrent.FutureTask<Void>> queue =
            new java.util.concurrent.ArrayBlockingQueue<>(32);
        final emu.grasscutter.data.binout.config.ConfigGlobalCombat previous = emu.grasscutter.data.GameData.getConfigGlobalCombat();
        final boolean previousScripts = emu.grasscutter.Grasscutter.getConfig().server.game.enableScriptInBigWorld;
        final AbilityData previousAbility;
        final emu.grasscutter.data.excels.MonsterAffixData previousAffix;
        final Scene scene, destination;
        final EntityMonster monster;

        MonsterSceneFixture(boolean guest) throws Exception {
            emu.grasscutter.Grasscutter.getConfig().server.game.enableScriptInBigWorld = false;
            var config = new emu.grasscutter.data.binout.config.ConfigGlobalCombat();
            config.getDefaultAbilities().setDefaultMPLevelAbilities(List.of(ABILITY));
            emu.grasscutter.data.GameData.setConfigGlobalCombat(config);
            var data = new AbilityData(); data.abilityName = ABILITY;
            data.onAdded = new AbilityModifierAction[] {clearAction()};
            previousAbility = emu.grasscutter.data.GameData.getAbilityDataMap().put(ABILITY, data);
            var affix = new emu.grasscutter.data.excels.MonsterAffixData();
            set(affix, affix.getClass(), "abilityName", new String[] {ABILITY});
            set(affix, affix.getClass(), "preAdd", true);
            previousAffix = emu.grasscutter.data.GameData.getMonsterAffixDataMap().put(AFFIX, affix);
            set(base.world, World.class, "nextEntityId", new java.util.concurrent.atomic.AtomicInteger());
            set(base.world, World.class, "scenes", new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<Scene>());
            set(base.world, World.class, "entity", allocate(EntityWorld.class));
            base.host.manager = new AbilityManager(base.host,
                task -> queue.add(new java.util.concurrent.FutureTask<Void>(task, null)));
            base.host.team.setEntity(allocate(EntityTeam.class));
            base.owner.team.setEntity(allocate(EntityTeam.class));
            scene = scene(3); destination = scene(4);
            base.host.setScene(scene); scene.getPlayers().add(base.host);
            if (guest) { base.owner.setScene(scene); scene.getPlayers().add(base.owner); }
            var monsterData = new emu.grasscutter.data.excels.monster.MonsterData();
            set(monsterData, monsterData.getClass(), "id", AFFIX);
            set(monsterData, monsterData.getClass(), "affix", List.of(AFFIX));
            monster = new EntityMonster(scene, monsterData, new Position(), new Position(), 1);
            assertNull(scene.getEntityById(monster.getId()), "Constructor queues before registration");
            assertEquals(1, queue.size());
            assertSame(base.host, monster.getInstancedAbilities().get(0).getPlayerOwner());
            monster.getGlobalAbilityValues().put("lease_mark", 7f);
            scene.getEntities().put(monster.getId(), monster);
        }
        private Scene scene(int id) throws Exception {
            var result = allocate(Scene.class);
            var data = new emu.grasscutter.data.excels.scene.SceneData();
            set(data, data.getClass(), "id", id);
            set(result, Scene.class, "world", base.world);
            set(result, Scene.class, "sceneData", data);
            set(result, Scene.class, "players", new java.util.concurrent.CopyOnWriteArrayList<Player>());
            set(result, Scene.class, "entities", new java.util.concurrent.ConcurrentHashMap<Integer, GameEntity>());
            set(result, Scene.class, "weaponEntities", new java.util.concurrent.ConcurrentHashMap<Integer, EntityWeapon>());
            set(result, Scene.class, "loadedGroups", new HashSet<>());
            var gate = Scene.class.getDeclaredField("playerEntryCallbacks").getType().getDeclaredConstructor();
            gate.setAccessible(true);
            set(result, Scene.class, "playerEntryCallbacks", gate.newInstance());
            set(result, Scene.class, "scriptManager", new emu.grasscutter.scripts.SceneScriptManager(result));
            base.world.registerScene(result);
            return result;
        }
        void departHost() {
            scene.removePlayer(base.host);
            base.host.setScene(destination); destination.getPlayers().add(base.host);
            assertSame(base.world, base.host.getWorld());
        }
        void drain() {
            for (int i = 0; i < 32 && !queue.isEmpty(); i++) queue.remove().run();
            assertTrue(queue.isEmpty(), "Bounded fixture must finish all child work");
        }
        public void close() {
            scene.getScriptManager().onDestroy(); destination.getScriptManager().onDestroy();
            emu.grasscutter.Grasscutter.getConfig().server.game.enableScriptInBigWorld = previousScripts;
            emu.grasscutter.data.GameData.setConfigGlobalCombat(previous);
            if (previousAbility == null) emu.grasscutter.data.GameData.getAbilityDataMap().remove(ABILITY);
            else emu.grasscutter.data.GameData.getAbilityDataMap().put(ABILITY, previousAbility);
            if (previousAffix == null) emu.grasscutter.data.GameData.getMonsterAffixDataMap().remove(AFFIX);
            else emu.grasscutter.data.GameData.getMonsterAffixDataMap().put(AFFIX, previousAffix);
        }
    }

    private static AbilityModifierAction clearAction() {
        var action = new AbilityModifierAction();
        action.type = AbilityModifierAction.Type.ClearGlobalValue; action.key = "lease_mark";
        return action;
    }

    /** Real pseudo-entity constructors and host initialization; only storage/transport are replaced. */
    static final class TeamFixture implements AutoCloseable {
        final Fixture base = new Fixture();
        final java.util.concurrent.ArrayBlockingQueue<java.util.concurrent.FutureTask<Void>> queue =
            new java.util.concurrent.ArrayBlockingQueue<>(32);
        final emu.grasscutter.data.binout.config.ConfigGlobalCombat previous = emu.grasscutter.data.GameData.getConfigGlobalCombat();
        final AbilityData previousAbility;
        final EntityTeam team;
        TeamFixture() throws Exception { this(false); }
        TeamFixture(boolean withScene) throws Exception {
            set(base.world, World.class, "nextEntityId", new java.util.concurrent.atomic.AtomicInteger());
            if (!withScene) base.owner.setScene(null);
            base.host.setScene(allocate(S.class));
            base.host.manager = new AbilityManager(base.host,
                task -> queue.add(new java.util.concurrent.FutureTask<Void>(task, null)));
            var config = new emu.grasscutter.data.binout.config.ConfigGlobalCombat();
            config.getDefaultAbilities().setDefaultTeamAbilities(List.of("RealTeamLifetimeFixture"));
            config.getDefaultAbilities().setDefaultMPLevelAbilities(List.of("RealTeamLifetimeFixture"));
            config.getDefaultAbilities().setLevelElementAbilities(List.of("RealTeamLifetimeFixture"));
            emu.grasscutter.data.GameData.setConfigGlobalCombat(config);
            var data = new AbilityData(); data.abilityName = "RealTeamLifetimeFixture";
            data.onAdded = new AbilityModifierAction[] {clearAction()};
            previousAbility = emu.grasscutter.data.GameData.getAbilityDataMap().put(data.abilityName, data);
            team = new EntityTeam(base.owner);
            team.getGlobalAbilityValues().put("lease_mark", 7f);
        }
        void drain() {
            for (int i = 0; i < 32 && !queue.isEmpty(); i++) queue.remove().run();
            assertTrue(queue.isEmpty(), "Bounded fixture must finish all child work");
        }
        public void close() {
            emu.grasscutter.data.GameData.setConfigGlobalCombat(previous);
            if (previousAbility == null) emu.grasscutter.data.GameData.getAbilityDataMap().remove("RealTeamLifetimeFixture");
            else emu.grasscutter.data.GameData.getAbilityDataMap().put("RealTeamLifetimeFixture", previousAbility);
        }
    }

    private static AbilityMixinData clearMixin() {
        var action = new AbilityModifierAction();
        action.type = AbilityModifierAction.Type.ClearGlobalValue; action.key = "lease_mark";
        var data = new AbilityMixinData(); data.type = AbilityMixinData.Type.AttachActionToModifierMixin;
        data.actions = new AbilityModifierAction[] {action};
        return data;
    }

    static final class Fixture {
        final P host = allocate(P.class), owner = allocate(P.class);
        final W world = allocate(W.class);
        final S scene = allocate(S.class);
        final E entity = allocate(E.class);
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final AbilityManager manager = new AbilityManager(host, queue::add);
        final Ability ability = allocate(Ability.class);
        Fixture() throws Exception {
            world.host = host; host.manager = manager;
            host.team = new emu.grasscutter.game.player.TeamManager();
            owner.team = new emu.grasscutter.game.player.TeamManager();
            host.setWorld(world); owner.setWorld(world);
            host.setSession(new GameSession(null)); owner.setSession(new GameSession(null));
            host.setScene(scene); owner.setScene(scene);
            scene.world = world; scene.entities = new HashMap<>();
            entity.scene = scene; entity.values = new HashMap<>();
            entity.abilities = new ArrayList<>(List.of(ability));
            entity.values.put("lease_mark", 7f); scene.entities.put(entity.getId(), entity);
            set(ability, Ability.class, "owner", entity);
            set(ability, Ability.class, "playerOwner", owner);
            set(ability, Ability.class, "manager", manager);
            var data = new AbilityData(); data.abilityName = "LifetimeFixture";
            set(ability, Ability.class, "data", data);
        }
        void queueClear() {
            var action = new AbilityModifierAction();
            action.type = AbilityModifierAction.Type.ClearGlobalValue; action.key = "lease_mark";
            manager.executeAction(ability, action, ByteString.EMPTY, entity);
            assertEquals(1, queue.size());
        }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
    }
    static final class P extends Player {
        AbilityManager manager; emu.grasscutter.game.player.TeamManager team;
        @Override public AbilityManager getAbilityManager() { return manager; }
        @Override public emu.grasscutter.game.player.TeamManager getTeamManager() { return team; }
        @Override public void sendPacket(BasePacket packet) {}
    }
    static final class W extends World {
        P host;
        W() { super(null); }
        @Override public Player getHost() { return host; }
    }
    static final class S extends Scene {
        W world; Map<Integer, GameEntity> entities; RuntimeException lookupFailure;
        S() { super(null, null); }
        @Override public World getWorld() { return world; }
        @Override public Player getHost() { return world.host; }
        @Override public GameEntity getEntityById(int id) {
            if (lookupFailure != null) throw lookupFailure;
            return entities.get(id);
        }
    }
    static final class E extends EntityMonster {
        S scene; Map<String, Float> values; List<Ability> abilities; int updates;
        E() { super(null, null, null, null, 1); }
        @Override public Scene getScene() { return scene; }
        @Override public List<Ability> getInstancedAbilities() { return abilities; }
        @Override public Map<String, Float> getGlobalAbilityValues() { return values; }
        @Override public void onAbilityValueUpdate() { updates++; }
    }
    private static <T> T allocate(Class<T> type) {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
