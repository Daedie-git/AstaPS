package emu.grasscutter.game.ability;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.UnknownFieldSet;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.config.ConfigContainer.GameOptions;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.AbilityData;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.data.binout.OpenConfigEntry;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.data.excels.avatar.AvatarTalentData;
import emu.grasscutter.game.ability.actions.ActionAvatarSkillStart;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.managers.energy.EnergyManager;
import emu.grasscutter.game.player.InvokeHandler;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.quest.QuestManager;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.World;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.*;
import emu.grasscutter.net.proto.AbilityAppliedAbilityOuterClass.AbilityAppliedAbility;
import emu.grasscutter.net.proto.AbilityInvokeArgumentOuterClass.AbilityInvokeArgument;
import emu.grasscutter.net.proto.AbilityInvokeEntryHeadOuterClass.AbilityInvokeEntryHead;
import emu.grasscutter.net.proto.AbilityInvokeEntryOuterClass.AbilityInvokeEntry;
import emu.grasscutter.net.proto.AbilityInvocationsNotifyOuterClass.AbilityInvocationsNotify;
import emu.grasscutter.net.proto.AbilityScalarValueEntryOuterClass.AbilityScalarValueEntry;
import emu.grasscutter.net.proto.AbilityStringOuterClass.AbilityString;
import emu.grasscutter.net.proto.ForwardTypeOuterClass.ForwardType;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.recv.*;
import emu.grasscutter.server.packet.send.PacketAbilityInvocationsNotify;
import emu.grasscutter.server.packet.send.PacketPlayerEnterSceneInfoNotify;
import emu.grasscutter.server.scheduler.ServerTaskScheduler;
import emu.grasscutter.utils.Utils;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
final class AmberSupportTest {
    private final Gson gson = new Gson();
    private RecordingPlayer player;
    private RecordingScene scene;
    private EntityAvatar amber;
    private EntityAvatar teammate;
    private Ability ability;
    private FakeTasks tasks;
    private AmberSupport.Engine engine;
    private long now;
    private float previousHeal;
    private float previousC6;
    private AvatarTalentData previousTalent;

    @BeforeEach
    void setup() throws Exception {
        previousHeal = GAME_OPTIONS.amberBurstHealHpRatio;
        previousC6 = GAME_OPTIONS.amberC6AttackMultiplier;
        GAME_OPTIONS.amberBurstHealHpRatio = 0.03f;
        GAME_OPTIONS.amberC6AttackMultiplier = 2f;
        previousTalent = GameData.getAvatarTalentDataMap().put(216, gson.fromJson(
                "{\"talentId\":216,\"openConfig\":\"Ambor_Talent_216\",\"paramList\":[0.15,0.15,10]}", AvatarTalentData.class));
        scene = allocate(RecordingScene.class);
        scene.entities = new HashMap<>();
        scene.packets = new ArrayList<>();
        var world = allocate(RecordingWorld.class);
        world.packets = scene.packets;
        set(scene, Scene.class, "world", world);
        player = allocate(RecordingPlayer.class);
        player.uid = 90001;
        player.packets = new ArrayList<>();
        set(player, Player.class, "scene", scene);
        set(player, Player.class, "teamManager", new TeamManager());
        set(player, Player.class, "abilityManager", new AbilityManager(player));
        set(player, Player.class, "energyManager", new EnergyManager(player));
        set(player, Player.class, "questManager", new QuestManager(player));
        set(world, World.class, "host", player);
        amber = avatar(1, 10000021, 10000f);
        teammate = avatar(2, 10000089, 20000f);
        avatar(3, 10000016, 15000f);
        avatar(4, 10000031, 15000f);
        ability = allocate(Ability.class);
        set(ability, Ability.class, "owner", amber);
        set(ability, Ability.class, "playerOwner", player);
        set(ability, Ability.class, "data", gson.fromJson(
                "{\"abilityName\":\"Avatar_Ambor_ArrowRain\",\"modifiers\":{}}", AbilityData.class));
        set(ability, Ability.class, "abilitySpecials", new Object2FloatOpenHashMap<String>());
        set(ability, Ability.class, "manager", player.getAbilityManager());
        amber.getInstancedAbilities().add(ability);
        tasks = new FakeTasks();
        now = 0;
        engine = new AmberSupport.Engine(() -> now, uid -> uid == player.uid ? player : null, tasks);
    }

    @AfterEach
    void restore() {
        GAME_OPTIONS.amberBurstHealHpRatio = previousHeal;
        GAME_OPTIONS.amberC6AttackMultiplier = previousC6;
        if (previousTalent == null) GameData.getAvatarTalentDataMap().remove(216);
        else GameData.getAvatarTalentDataMap().put(216, previousTalent);
    }

    private void cast() { engine.cast(player, amber, ability, 10017, 0.03f); }
    private void advance(long milliseconds) {
        now += milliseconds;
        for (var callback : new ArrayList<>(tasks.active.values())) callback.run();
    }

    @Test
    void sixRealTimeTicksHealEntirePartyThroughActualHealHandlerAndPackets() throws Exception {
        cast();
        for (int i = 0; i < 60; i++) advance(200);
        for (var member : player.getTeamManager().getActiveTeam()) assertEquals(2800f, hp(member), 0.01f);
        assertTrue(tasks.active.isEmpty());
        assertEquals(24, scene.packets.stream().filter(p -> p instanceof emu.grasscutter.server.packet.send.PacketEvtBeingHealedNotify).count());
        var packet = scene.packets.stream().filter(p -> p instanceof emu.grasscutter.server.packet.send.PacketEvtBeingHealedNotify).findFirst().orElseThrow();
        var notify = CombatInvocationsNotifyOuterClass.CombatInvocationsNotify.parseFrom(packet.getData());
        var heal = EvtBeingHealedNotifyOuterClass.EvtBeingHealedNotify.parseFrom(notify.getInvokeList(0).getCombatData());
        assertEquals(amber.getId(), heal.getSourceId());
        assertEquals(300f, heal.getRealHealAmount(), 0.01f);
        advance(20000);
        assertEquals(2800f, hp(teammate), 0.01f);
    }

    @Test
    void doesNotHealAtServerTickSpeedAndSkipsMissedIntervalsWithoutCatchUp() {
        cast();
        advance(1999);
        assertEquals(1000f, hp(teammate));
        advance(1);
        assertEquals(1300f, hp(teammate), 0.01f);
        advance(8000);
        assertEquals(1600f, hp(teammate), 0.01f);
        advance(1);
        assertEquals(1600f, hp(teammate), 0.01f);
        advance(5000);
        assertTrue(tasks.active.isEmpty());
        assertEquals(1600f, hp(teammate), 0.01f);
    }

    @Test
    void snapshotsCasterHpAndHealingBonusButUsesRecipientBonusAtEachTick() {
        amber.setFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD, 0.5f);
        cast();
        amber.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, 20000f);
        teammate.setFightProperty(FightProperty.FIGHT_PROP_HEALED_ADD, 0.2f);
        advance(2000);
        assertEquals(1540f, hp(teammate), 0.01f);
        assertEquals(1450f, hp(amber), 0.01f);
    }

    @Test
    void refreshCancelsOldTaskAndStaleCallbackCannotHealOrCancelReplacement() {
        cast();
        Runnable stale = tasks.active.values().iterator().next();
        advance(2000);
        cast();
        assertEquals(1, tasks.active.size());
        stale.run();
        assertEquals(1300f, hp(teammate), 0.01f);
        advance(2000);
        assertEquals(1600f, hp(teammate), 0.01f);
        assertEquals(1, tasks.active.size());
    }

    @Test
    void duplicateConfirmationsDoNotRefreshAndSwitchingActiveCharacterDoesNotCancel() {
        cast();
        advance(1000);
        cast();
        assertEquals(1, tasks.nextId);
        player.getTeamManager().setCurrentCharacterIndex(1);
        advance(1000);
        assertEquals(1300f, hp(teammate), 0.01f);
    }

    @Test
    void skipsDeadRestrictedAndDuplicateRecipientsAndClampsFullHp() throws Exception {
        player.getTeamManager().getActiveTeam().add(teammate);
        teammate.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 19950f);
        var dead = player.getTeamManager().getActiveTeam().get(2);
        dead.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 0f);
        set(dead, GameEntity.class, "isDead", true);
        var restricted = player.getTeamManager().getActiveTeam().get(3);
        restricted.setRestrictedFromHealing(true);
        cast();
        advance(2000);
        assertEquals(20000f, hp(teammate));
        assertEquals(0f, hp(dead));
        assertEquals(1000f, hp(restricted));
    }

    @Test
    void paysNormalBondOfLifeBeforeHpInsteadOfBypassingIt() {
        teammate.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS, 200f);
        cast();
        advance(2000);
        assertEquals(0f, teammate.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS));
        assertEquals(1100f, hp(teammate), 0.01f);
    }

    @Test
    void neverOverridesArlecchinoCombatHealingRestriction() throws Exception {
        set(teammate.getAvatar(), Avatar.class, "avatarId", 10000096);
        teammate.getGlobalAbilityValues().put("_ABILITY_Avatar_ForbidFoodHeal", 1f);
        cast();
        advance(2000);
        assertEquals(1000f, hp(teammate));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void logoutSceneChangeCasterDeathAndPartyChangeCancel(int reason) throws Exception {
        cast();
        switch (reason) {
            case 0 -> engine.clear(player);
            case 1 -> set(player, Player.class, "scene", allocate(RecordingScene.class));
            case 2 -> set(amber, GameEntity.class, "isDead", true);
            case 3 -> player.getTeamManager().getActiveTeam().remove(teammate);
        }
        advance(2000);
        assertTrue(tasks.active.isEmpty());
        assertEquals(1000f, hp(teammate));
    }

    @Test
    void entityCleanupOnlyCancelsCasterAndOldSessionCleanupCannotCancelNewSession() throws Exception {
        cast();
        engine.clearCaster(player, teammate.getId());
        assertEquals(1, tasks.active.size());
        var other = allocate(RecordingPlayer.class);
        other.uid = player.uid;
        engine.clear(other);
        assertEquals(1, tasks.active.size());
        engine.clearCaster(player, amber.getId());
        assertTrue(tasks.active.isEmpty());
    }

    @Test
    void rejectsOtherCharactersSkillsForeignCastersAndInvalidAmounts() throws Exception {
        engine.cast(player, amber, ability, 10032, 0.03f);
        engine.cast(player, teammate, ability, 10017, 0.03f);
        for (float ratio : new float[] {0, -1, 2, Float.NaN, Float.POSITIVE_INFINITY})
            engine.cast(player, amber, ability, 10017, ratio);
        assertTrue(tasks.active.isEmpty());
        amber.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, Float.MAX_VALUE);
        amber.setFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD, Float.MAX_VALUE);
        cast();
        assertTrue(tasks.active.isEmpty());
    }

    @Test
    void doublesOnlyC6AttackAndLeavesOtherCharactersAndC0Alone() throws Exception {
        assertEquals(0.3f, AmberSupport.c6Attack(amber.getAvatar(), 2f), 0.0001f);
        assertNull(AmberSupport.c6Attack(teammate.getAvatar(), 2f));
        assertNull(AmberSupport.c6Attack(amber.getAvatar(), 1f));
        assertNull(AmberSupport.c6Attack(amber.getAvatar(), Float.NaN));
        set(amber.getAvatar(), Avatar.class, "talentIdList", new HashSet<Integer>());
        assertNull(AmberSupport.c6Attack(amber.getAvatar(), 2f));
    }

    @Test
    void sceneAppliedOverridePreservesUnlockSpeedDurationAndUnknownFieldsWithoutDuplicatingAttack() {
        var original = applied();
        var tuned = AmberSupport.tuneAppliedAbility(amber.getAvatar(), original);
        assertEquals(0.3f, value(tuned, AmberSupport.C6_ATTACK), 0.0001f);
        assertEquals(0.15f, value(tuned, "Ambor_Talent_216_P1_MoveSpeedUp"));
        assertEquals(10f, value(tuned, "Ambor_Talent_216_P3_BuffTimer"));
        assertEquals(1f, value(tuned, "Ambor_Talent_216"));
        assertEquals(original.getUnknownFields(), tuned.getUnknownFields());
        assertEquals(original.getOverrideMapCount(), tuned.getOverrideMapCount());
        assertEquals(tuned, AmberSupport.tuneAppliedAbility(amber.getAvatar(), tuned));
    }

    @Test
    void actualSceneEntryAddsC6OverrideAlongsideNativeUnlock() {
        var old = GameData.getOpenConfigEntries().put("Ambor_Talent_216", new OpenConfigEntry("Ambor_Talent_216",
                gson.fromJson("[{\"$type\":\"UnlockTalentParam\",\"abilityName\":\"Avatar_Ambor_ArrowRain\",\"talentParam\":\"Ambor_Talent_216\"}]",
                        emu.grasscutter.data.ResourceLoader.OpenConfigData[].class)));
        try {
            assertTrue(amber.getAvatar().getTalentIdList().contains(216));
            assertEquals("Ambor_Talent_216", GameData.getAvatarTalentDataMap().get(216).getOpenConfig());
            assertNotNull(GameData.getOpenConfigEntries().get("Ambor_Talent_216").getTalentParamEntries());
            var info = AbilitySyncStateInfoOuterClass.AbilitySyncStateInfo.newBuilder();
            PacketPlayerEnterSceneInfoNotify.buildAvatarAbilityVars(amber, info);
            var applied = info.getAppliedAbilitiesList().stream()
                    .filter(a -> a.getAbilityName().getHash() == Utils.abilityHash(AmberSupport.BURST_ABILITY)).findFirst().orElseThrow();
            assertEquals(1f, value(applied, "Ambor_Talent_216"));
            assertEquals(0.3f, value(applied, AmberSupport.C6_ATTACK), 0.0001f);
        } finally {
            if (old == null) GameData.getOpenConfigEntries().remove("Ambor_Talent_216");
            else GameData.getOpenConfigEntries().put("Ambor_Talent_216", old);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void allActualAbilityIngressHandlersProcessAndForwardTheSameTunedValue(int handler) throws Exception {
        var invoke = invoke(AbilityInvokeArgument.AbilityInvokeArgument_ABILITY_META_OVERRIDE_PARAM,
                scalar(AmberSupport.C6_ATTACK, 0.15f).toByteString());
        var forwarding = new RecordingInvoke();
        set(player, Player.class, "abilityInvokeHandler", forwarding);
        set(player, Player.class, "clientAbilityInitFinishHandler", forwarding);
        var session = allocate(GameSession.class);
        set(session, GameSession.class, "player", player);
        switch (handler) {
            case 0 -> new HandlerAbilityInvocationsNotify().handle(session, new byte[0],
                    AbilityInvocationsNotify.newBuilder().addInvokes(invoke).build().toByteArray());
            case 1 -> new HandlerClientAbilityInitFinishNotify().handle(session, new byte[0],
                    ClientAbilityInitFinishNotifyOuterClass.ClientAbilityInitFinishNotify.newBuilder()
                            .addInvokes(invoke).build().toByteArray());
            case 2 -> new HandlerClientAbilityChangeNotify().handle(session, new byte[0],
                    ClientAbilityChangeNotifyOuterClass.ClientAbilityChangeNotify.newBuilder()
                            .addInvokes(invoke).build().toByteArray());
            default -> {
                // Empty acknowledgments use the session; provide a recording session for this handler.
                session = allocate(RecordingSession.class);
                set(session, GameSession.class, "player", player);
                new HandlerClientAbilitiesInitFinishCombineNotify().handle(session, new byte[0],
                        ClientAbilitiesInitFinishCombineNotifyOuterClass.ClientAbilitiesInitFinishCombineNotify.newBuilder()
                                .addEntityInvokeList(EntityAbilityInvokeEntryOuterClass.EntityAbilityInvokeEntry.newBuilder()
                                        .addInvokes(invoke)).build().toByteArray());
            }
        }
        assertEquals(0.3f, ability.getAbilitySpecials().getFloat(AmberSupport.C6_ATTACK), 0.0001f);
        assertEquals(0.3f, AbilityScalarValueEntry.parseFrom(forwarding.entry.getAbilityData()).getFloatValue(), 0.0001f);
        assertEquals(ForwardType.ForwardType_FORWARD_TO_ALL, forwarding.entry.getForwardType());
        assertSame(forwarding.entry, AmberSupport.tuneInvoke(player, forwarding.entry));
    }

    @Test
    void canonicalizesReinitAndAddAbilityWithoutChangingOtherParameters() throws Exception {
        var reinit = AbilityMetaReInitOverrideMapOuterClass.AbilityMetaReInitOverrideMap.newBuilder()
                .addOverrideMap(scalar(AmberSupport.C6_ATTACK, 0.15f))
                .addOverrideMap(scalar("Ambor_Talent_216_P1_MoveSpeedUp", 0.15f));
        var original = invoke(AbilityInvokeArgument.AbilityInvokeArgument_ABILITY_META_REINIT_OVERRIDEMAP,
                reinit.build().toByteString());
        var tuned = AmberSupport.tuneInvoke(player, original);
        var parsed = AbilityMetaReInitOverrideMapOuterClass.AbilityMetaReInitOverrideMap.parseFrom(tuned.getAbilityData());
        assertEquals(0.3f, parsed.getOverrideMap(0).getFloatValue(), 0.0001f);
        assertEquals(reinit.getOverrideMap(1), parsed.getOverrideMap(1));
        player.getAbilityManager().onAbilityInvoke(tuned);
        assertEquals(0.3f, ability.getAbilitySpecials().getFloat(AmberSupport.C6_ATTACK), 0.0001f);
        var add = AbilityMetaAddAbilityOuterClass.AbilityMetaAddAbility.newBuilder().setAbility(applied()).build();
        var patched = AmberSupport.tuneInvoke(player,
                invoke(AbilityInvokeArgument.AbilityInvokeArgument_ABILITY_META_ADD_NEW_ABILITY, add.toByteString()));
        assertEquals(0.3f, value(AbilityMetaAddAbilityOuterClass.AbilityMetaAddAbility.parseFrom(patched.getAbilityData())
                .getAbility(), AmberSupport.C6_ATTACK), 0.0001f);
    }

    @Test
    void oldConfigurationDisablesCustomizationsAndNewValuesParse() {
        var defaults = gson.fromJson("{}", GameOptions.class);
        assertEquals(0f, defaults.amberBurstHealHpRatio);
        assertEquals(1f, defaults.amberC6AttackMultiplier);
        var tuned = gson.fromJson("{\"amberBurstHealHpRatio\":0.03,\"amberC6AttackMultiplier\":2}", GameOptions.class);
        assertEquals(0.03f, tuned.amberBurstHealHpRatio);
        assertEquals(2f, tuned.amberC6AttackMultiplier);
    }

    @Test
    void actualConfirmedSkillActionArmsHealingWithoutRequiringC6() throws Exception {
        set(amber.getAvatar(), Avatar.class, "talentIdList", new HashSet<Integer>());
        Object oldServer = field(Grasscutter.class, "gameServer").get(null);
        var server = allocate(GameServer.class);
        var scheduler = new ServerTaskScheduler();
        set(server, GameServer.class, "players", Map.of(player.uid, player));
        set(server, GameServer.class, "scheduler", scheduler);
        field(Grasscutter.class, "gameServer").set(null, server);
        try {
            var action = new AbilityModifierAction();
            action.skillID = 10017;
            action.costStaminaRatio = new emu.grasscutter.data.common.DynamicFloat(1f);
            new ActionAvatarSkillStart().execute(ability, action, com.google.protobuf.ByteString.EMPTY, amber);
            assertNotNull(scheduler.getTask(0), "Actual burst confirmation must arm the party heal");
            var liveEngine = field(AmberSupport.class, "ENGINE").get(null);
            var runs = (Map<?, ?>) field(AmberSupport.Engine.class, "runs").get(liveEngine);
            Object run = runs.get(player.uid);
            field(run.getClass(), "castAt").set(run, System.nanoTime() / 1_000_000 - 2001);
            scheduler.runTasks();
            assertEquals(1300f, hp(teammate), 0.01f);
            AmberSupport.clearPlayerState(player);
            assertNull(scheduler.getTask(0));
        } finally {
            AmberSupport.clearPlayerState(player);
            field(Grasscutter.class, "gameServer").set(null, oldServer);
        }
    }

    @Test
    void deployedFeatureClassesComeFromExpectedJarWhenRequested() throws Exception {
        String path = System.getProperty("combatJar");
        if (path == null) return;
        for (var type : new Class<?>[] {AmberSupport.class, AbilityManager.class, Ability.class,
                ActionAvatarSkillStart.class, PacketPlayerEnterSceneInfoNotify.class, GameOptions.class,
                HandlerAbilityInvocationsNotify.class, HandlerClientAbilityInitFinishNotify.class,
                HandlerClientAbilityChangeNotify.class, HandlerClientAbilitiesInitFinishCombineNotify.class})
            assertEquals(java.nio.file.Path.of(path).toRealPath(), java.nio.file.Path.of(type.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toRealPath());
    }

    private EntityAvatar avatar(int id, int avatarId, float maximum) throws Exception {
        var data = new Avatar();
        set(data, Avatar.class, "avatarId", avatarId);
        set(data, Avatar.class, "guid", (long) player.uid * 100 + id);
        set(data, Avatar.class, "owner", player);
        set(data, Avatar.class, "avatarData", gson.fromJson("{\"name\":\"" + (avatarId == 10000021 ? "Ambor" : "Other") + "\"}", AvatarData.class));
        set(data, Avatar.class, "skillDepot", gson.fromJson("{\"talents\":[216]}", emu.grasscutter.data.excels.avatar.AvatarSkillDepotData.class));
        set(data, Avatar.class, "talentIdList", new HashSet<>(avatarId == 10000021 ? Set.of(216) : Set.of()));
        set(data, Avatar.class, "proudSkillList", new HashSet<>());
        var entity = allocate(EntityAvatar.class);
        set(entity, EntityAvatar.class, "avatar", data);
        set(entity, GameEntity.class, "scene", scene);
        set(entity, GameEntity.class, "globalAbilityValues", new HashMap<String, Float>());
        set(entity, GameEntity.class, "instancedModifiers", new HashMap<>());
        set(entity, GameEntity.class, "instancedAbilities", new ArrayList<Ability>());
        entity.setId(id);
        entity.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, maximum);
        entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 1000f);
        scene.entities.put(id, entity);
        player.getTeamManager().getActiveTeam().add(entity);
        return entity;
    }

    private static float hp(EntityAvatar entity) { return entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP); }
    private static AbilityScalarValueEntry scalar(String key, float value) {
        return AbilityScalarValueEntry.newBuilder().setKey(AbilityString.newBuilder().setHash(Utils.abilityHash(key)))
                .setFloatValue(value).build();
    }
    private AbilityAppliedAbility applied() {
        return AbilityAppliedAbility.newBuilder()
                .setAbilityName(AbilityString.newBuilder().setHash(Utils.abilityHash(AmberSupport.BURST_ABILITY)))
                .addOverrideMap(scalar(AmberSupport.C6_ATTACK, 0.15f))
                .addOverrideMap(scalar("Ambor_Talent_216_P1_MoveSpeedUp", 0.15f))
                .addOverrideMap(scalar("Ambor_Talent_216_P3_BuffTimer", 10f))
                .addOverrideMap(scalar("Ambor_Talent_216", 1f))
                .setUnknownFields(UnknownFieldSet.newBuilder().addField(999,
                        UnknownFieldSet.Field.newBuilder().addVarint(7).build()).build()).build();
    }
    private static float value(AbilityAppliedAbility applied, String key) {
        return applied.getOverrideMapList().stream().filter(e -> e.getKey().getStr().equals(key)
                || e.getKey().getHash() == Utils.abilityHash(key)).findFirst().orElseThrow().getFloatValue();
    }
    private AbilityInvokeEntry invoke(AbilityInvokeArgument type, com.google.protobuf.ByteString data) {
        return AbilityInvokeEntry.newBuilder().setEntityId(amber.getId()).setArgumentType(type)
                .setHead(AbilityInvokeEntryHead.newBuilder().setInstancedAbilityId(1))
                .setForwardType(ForwardType.ForwardType_FORWARD_TO_ALL_EXCEPT_CUR).setAbilityData(data).build();
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
        field(type, name).set(owner, value);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(((Unsafe) field(Unsafe.class, "theUnsafe").get(null)).allocateInstance(type));
    }
    private static final class FakeTasks implements AmberSupport.Tasks {
        final Map<Integer, Runnable> active = new HashMap<>(); int nextId;
        public int repeat(Runnable task, int period, int delay) {
            assertEquals(1, period); assertEquals(1, delay);
            int id = nextId++; active.put(id, task); return id;
        }
        public void cancel(int id) { active.remove(id); }
    }
    private static final class RecordingPlayer extends Player {
        int uid; List<BasePacket> packets;
        @Override public int getUid() { return uid; }
        @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
    }
    private static final class RecordingScene extends Scene {
        Map<Integer, GameEntity> entities; List<BasePacket> packets;
        private RecordingScene() { super(null, null); }
        @Override public GameEntity getEntityById(int id) { return entities.get(id); }
        @Override public void broadcastPacket(BasePacket packet) { packets.add(packet); }
    }
    private static final class RecordingWorld extends World {
        List<BasePacket> packets;
        private RecordingWorld() { super((Player) null); }
        @Override public void broadcastPacket(BasePacket packet) { packets.add(packet); }
    }
    private static final class RecordingInvoke extends InvokeHandler<AbilityInvokeEntry> {
        AbilityInvokeEntry entry;
        private RecordingInvoke() { super(PacketAbilityInvocationsNotify.class); }
        @Override public synchronized void addEntry(ForwardType type, AbilityInvokeEntry entry) { this.entry = entry; }
        @Override public synchronized void update(Player player) {}
    }
    private static final class RecordingSession extends GameSession {
        private RecordingSession() { super(null); }
        @Override public void send(BasePacket packet) {}
    }
}
