package emu.grasscutter.game;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import com.google.protobuf.ByteString;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.binout.*;
import emu.grasscutter.data.common.DynamicFloat;
import emu.grasscutter.data.excels.avatar.AvatarSkillDepotData;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.ability.AbilityManager;
import emu.grasscutter.game.ability.mixins.CostStaminaMixin;
import emu.grasscutter.game.managers.SatiationManager;
import emu.grasscutter.game.managers.energy.EnergyManager;
import emu.grasscutter.server.packet.recv.HandlerCombatInvocationsNotify;
import emu.grasscutter.net.proto.CombatInvocationsNotifyOuterClass.CombatInvocationsNotify;
import emu.grasscutter.net.proto.CombatInvokeEntryOuterClass.CombatInvokeEntry;
import emu.grasscutter.net.proto.CombatTypeArgumentOuterClass.CombatTypeArgument;
import emu.grasscutter.net.proto.MotionInfoOuterClass.MotionInfo;
import emu.grasscutter.net.proto.VectorOuterClass.Vector;
import emu.grasscutter.net.proto.EvtBeingHitInfoOuterClass.EvtBeingHitInfo;
import emu.grasscutter.net.proto.AttackResultOuterClass.AttackResult;
import emu.grasscutter.server.packet.send.PacketCombatInvocationsNotify;
import emu.grasscutter.server.packet.send.PacketAbilityInvocationsNotify;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.game.ability.actions.ActionReviveElemEnergy;
import emu.grasscutter.game.ability.mixins.ReviveElemEnergyMixin;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.managers.stamina.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.EntityMoveInfoOuterClass.EntityMoveInfo;
import emu.grasscutter.net.proto.EntityFightPropChangeReasonNotifyOuterClass.EntityFightPropChangeReasonNotify;
import emu.grasscutter.net.proto.EntityFightPropUpdateNotifyOuterClass.EntityFightPropUpdateNotify;
import emu.grasscutter.server.packet.send.PacketEntityFightPropUpdateNotify;
import emu.grasscutter.net.proto.MotionStateOuterClass.MotionState;
import emu.grasscutter.net.proto.PlayerDieTypeOuterClass.PlayerDieType;
import emu.grasscutter.server.event.entity.EntityDamageEvent;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketEntityFightPropChangeReasonNotify;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
class CombatAuditTest {
    @Test void lockHpStopsSublethalAndLethalHitsButNotExplicitCosts() throws Exception {
        var f = fixture();
        for (float damage : new float[]{99, 100, 101}) {
            f.entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 100);
            f.entity.setLockHP(true);
            f.entity.damage(damage, 0, ElementType.None);
            assertEquals(100, f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        }
        f.entity.loseHpByAbility(10, false);
        assertEquals(90, f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        f.entity.loseHpByAbility(100, true);
        assertEquals(90, f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        f.entity.setLockHP(false);
        f.entity.damage(10, 0, ElementType.None);
        assertEquals(80, f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
    }

    @Test void rejectedMovementDoesNotPoisonDrowningTarget() throws Exception {
        var f = fixture();
        var remote = allocate(TestEntity.class);
        remote.setId(999);
        set(f.stamina, StaminaManager.class, "cachedSession", f.session);
        set(f.stamina, StaminaManager.class, "cachedEntity", f.entity);
        set(f.stamina, StaminaManager.class, "currentState", MotionState.MotionState_MOTION_SWIM_MOVE);
        f.stamina.handleCombatInvocationsNotify(f.session, EntityMoveInfo.getDefaultInstance(), remote);
        assertSame(f.entity, get(f.stamina, StaminaManager.class, "cachedEntity"));
        var drown = StaminaManager.class.getDeclaredMethod("handleDrowning");
        drown.setAccessible(true);
        drown.invoke(f.stamina);
        assertSame(f.entity, f.stamina.killed);
    }

    @Test void drowningRejectsStaleAvatarAfterSwitch() throws Exception {
        var f = fixture();
        var old = f.entity;
        set(f.stamina, StaminaManager.class, "cachedSession", f.session);
        set(f.stamina, StaminaManager.class, "cachedEntity", old);
        set(f.stamina, StaminaManager.class, "currentState", MotionState.MotionState_MOTION_SWIM_MOVE);
        f.team.getActiveTeam().add(second(f));
        f.team.setCurrentCharacterIndex(1);
        var drown = StaminaManager.class.getDeclaredMethod("handleDrowning");
        drown.setAccessible(true);
        drown.invoke(f.stamina);
        assertNull(f.stamina.killed);
    }

    @Test void manualSwitchRejectsDeadOwnedAvatar() throws Exception {
        var f = fixture();
        var dead = second(f);
        dead.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 0);
        set(dead, GameEntity.class, "isDead", true);
        f.team.getActiveTeam().add(dead);
        f.team.changeAvatar(dead.getAvatar().getGuid());
        assertEquals(0, f.team.getCurrentCharacterIndex());
        assertEquals(0, f.scene.replacements);
    }

    @Test void actionAndMixinRefundCapAndReportAppliedDelta() throws Exception {
        boolean energyUsage = GAME_OPTIONS.energyUsage;
        GAME_OPTIONS.energyUsage = true;
        try {
            for (boolean mixin : new boolean[]{false, true}) {
                for (float start : new float[]{0, 79, 80}) {
                    var f = fixture();
                    f.entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_FIRE_ENERGY, start);
                    refund(f, mixin);
                    assertEquals(Math.min(80, start + 3), f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_FIRE_ENERGY));
                    var reasons = f.scene.packets.stream().filter(PacketEntityFightPropChangeReasonNotify.class::isInstance).toList();
                    if (start == 80) assertTrue(reasons.isEmpty());
                    else {
                        assertEquals(1, reasons.size());
                        var notify = EntityFightPropChangeReasonNotify.parseFrom(reasons.get(0).getData());
                        assertEquals(Math.min(3, 80 - start), notify.getPropDelta());
                        assertEquals(emu.grasscutter.net.proto.PropChangeReasonOuterClass.PropChangeReason
                                .PropChangeReason_PROP_CHANGE_ABILITY, notify.getReason());
                        assertEquals(emu.grasscutter.net.proto.ChangeEnergyReasonOuterClass.ChangeEnergyReason
                                .ChangeEnergyReason_CHANGE_ENERGY_ABILITY, notify.getChangeEnergyReson());
                        var updates = f.scene.packets.stream().filter(PacketEntityFightPropUpdateNotify.class::isInstance).toList();
                        assertEquals(1, updates.size());
                        assertEquals(Math.min(80, start + 3), EntityFightPropUpdateNotify.parseFrom(updates.get(0).getData())
                                .getFightPropMapOrThrow(FightProperty.FIGHT_PROP_CUR_FIRE_ENERGY.getId()));
                    }
                }
            }
        } finally { GAME_OPTIONS.energyUsage = energyUsage; }
    }

    @Test void cappedEnergyPreservesExistingCallerReason() throws Exception {
        boolean energyUsage = GAME_OPTIONS.energyUsage;
        GAME_OPTIONS.energyUsage = true;
        try {
            var f = fixture();
            var reason = emu.grasscutter.net.proto.PropChangeReasonOuterClass.PropChangeReason
                    .PropChangeReason_PROP_CHANGE_ENERGY_BALL;
            f.entity.addEnergy(3, reason, false);
            var packet = f.scene.packets.stream().filter(PacketEntityFightPropChangeReasonNotify.class::isInstance)
                    .findFirst().orElseThrow();
            var notify = EntityFightPropChangeReasonNotify.parseFrom(packet.getData());
            assertEquals(9, notify.getPropDelta());
            assertEquals(reason, notify.getReason());
            assertEquals(emu.grasscutter.net.proto.ChangeEnergyReasonOuterClass.ChangeEnergyReason
                    .ChangeEnergyReason_CHANGE_ENERGY_NONE, notify.getChangeEnergyReson());
        } finally { GAME_OPTIONS.energyUsage = energyUsage; }
    }

    @Test void fallSamplesAreIsolatedBetweenSessionsAndConsumedOnce() throws Exception {
        var a = fixture();
        var b = fixture();
        var handler = new ClockCombat();
        movement(handler, a, MotionState.MotionState_MOTION_LAND_SPEED, -27);
        movement(handler, b, MotionState.MotionState_MOTION_FALL_ON_GROUND, 0);
        assertEquals(100, b.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        movement(handler, a, MotionState.MotionState_MOTION_FALL_ON_GROUND, 0);
        assertEquals(34, a.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP), .001f);
        movement(handler, a, MotionState.MotionState_MOTION_FALL_ON_GROUND, 0);
        assertEquals(34, a.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP), .001f);
    }

    @Test void standaloneHitAppliesDamageWithoutWaitingForUnion() throws Exception {
        var f = fixture();
        hit(new HandlerCombatInvocationsNotify(), f, 10);
        assertEquals(90, f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertEquals(1, f.scene.attacks);
    }

    @Test void staminaExecutionsAt600msAreNotFreeAndStreamsDoNotShareTiming() throws Exception {
        var f = fixture();
        var ability = testAbility(f);
        var mixin = new AbilityMixinData();
        mixin.costStaminaDelta = new DynamicFloat(1);
        var handler = new ClockStamina();
        handler.now = 10000;
        handler.execute(ability, mixin, ByteString.EMPTY, f.entity);
        assertEquals(0, f.stamina.spent);
        handler.now += 600;
        handler.execute(ability, mixin, ByteString.EMPTY, f.entity);
        assertEquals(-60, f.stamina.spent);
        handler.execute(testAbility(f), mixin, ByteString.EMPTY, f.entity);
        assertEquals(-60, f.stamina.spent);
        handler.now += 600;
        handler.execute(ability, mixin, ByteString.EMPTY, f.entity);
        assertEquals(-120, f.stamina.spent);
    }

    @Test void negativeStaminaDeltaRemainsAnElapsedTimeRefund() throws Exception {
        var f = fixture(); var ability = testAbility(f);
        var mixin = new AbilityMixinData(); mixin.costStaminaDelta = new DynamicFloat(-1);
        var handler = new ClockStamina(); handler.now = 10000;
        assertTrue(handler.execute(ability, mixin, ByteString.EMPTY, f.entity));
        handler.now += 600;
        assertTrue(handler.execute(ability, mixin, ByteString.EMPTY, f.entity));
        assertEquals(60, f.stamina.spent);
    }

    @Test void respawnRestoresDeathStateAndAllowsNormalHealing() throws Exception {
        var f = fixture();
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 0);
        set(f.entity, GameEntity.class, "isDead", true);
        f.team.getActiveTeam().add(second(f));
        var restore = TeamManager.class.getDeclaredMethod("restoreTeamForRespawn");
        restore.setAccessible(true);
        restore.invoke(f.team);
        assertTrue(f.entity.isAlive());
        assertEquals(40, f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertTrue(f.team.healAvatar(f.entity.getAvatar(), 0, 1000));
        assertEquals(50, f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
        assertTrue(f.team.getActiveTeam().get(1).isAlive());
        assertEquals(40, f.team.getActiveTeam().get(1).getFightProperty(FightProperty.FIGHT_PROP_CUR_HP));
    }

    @Test void monsterEnergyEventIsNotMultipliedByScenePlayers() throws Exception {
        for (int players : new int[]{1, 2, 4}) {
            var f = fixture();
            int[] events = {0};
            for (int i = 0; i < players; i++) {
                var player = i == 0 ? f.player : allocate(TestPlayer.class);
                player.scene = f.scene;
                if (i != 0) f.scene.players.add(player);
                set(player, Player.class, "energyManager", new CountingEnergy(player, events));
            }
            var monster = allocate(TestMonster.class);
            monster.props = new Int2FloatOpenHashMap();
            set(monster, GameEntity.class, "scene", f.scene);
            monster.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 100);
            monster.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, 100);
            monster.damage(10, 0, ElementType.None);
            assertEquals(1, events[0], "one shared energy event for " + players + " players");
        }
    }

    static Ability testAbility(Fixture f) throws Exception {
        var ability = allocate(Ability.class);
        set(ability, Ability.class, "owner", f.entity);
        set(ability, Ability.class, "playerOwner", f.player);
        set(ability, Ability.class, "data", new AbilityData());
        set(ability, Ability.class, "abilitySpecials", new Object2FloatOpenHashMap<String>());
        return ability;
    }

    static void hit(HandlerCombatInvocationsNotify handler, Fixture f, float damage) throws Exception {
        var attack = AttackResult.newBuilder().setAttackerId(f.entity.getId())
                .setDefenseId(f.entity.getId()).setDamage(damage).build();
        var entry = CombatInvokeEntry.newBuilder()
                .setArgumentType(CombatTypeArgument.CombatTypeArgument_COMBAT_EVT_BEING_HIT)
                .setCombatData(EvtBeingHitInfo.newBuilder().setAttackResult(attack).build().toByteString());
        handler.handle(f.session, new byte[0], CombatInvocationsNotify.newBuilder().addInvokeList(entry).build().toByteArray());
    }

    static void movement(HandlerCombatInvocationsNotify handler, Fixture f, MotionState state, float speed) throws Exception {
        var info = EntityMoveInfo.newBuilder().setEntityId(f.entity.getId())
                .setMotionInfo(MotionInfo.newBuilder().setState(state).setSpeed(Vector.newBuilder().setY(speed)));
        var entry = CombatInvokeEntry.newBuilder().setArgumentType(CombatTypeArgument.CombatTypeArgument_ENTITY_MOVE)
                .setCombatData(info.build().toByteString());
        handler.handle(f.session, new byte[0], CombatInvocationsNotify.newBuilder().addInvokeList(entry).build().toByteArray());
    }

    static void refund(Fixture f, boolean mixin) throws Exception {
        var ability = allocate(Ability.class);
        set(ability, Ability.class, "owner", f.entity);
        set(ability, Ability.class, "data", new AbilityData());
        set(ability, Ability.class, "abilitySpecials", new Object2FloatOpenHashMap<String>());
        if (mixin) {
            var data = new AbilityMixinData();
            data.ratio = new DynamicFloat(3);
            assertTrue(new ReviveElemEnergyMixin().execute(ability, data, ByteString.EMPTY, f.entity));
        } else {
            var data = new AbilityModifier.AbilityModifierAction();
            data.ratio = new DynamicFloat(3);
            assertTrue(new ActionReviveElemEnergy().execute(ability, data, ByteString.EMPTY, f.entity));
        }
    }

    static Fixture fixture() throws Exception {
        var f = new Fixture();
        f.player = allocate(TestPlayer.class);
        f.scene = allocate(TestScene.class);
        f.scene.packets = new ArrayList<>();
        f.scene.entities = new HashMap<>();
        f.scene.players = new ArrayList<>(List.of(f.player));
        f.scene.world = allocate(TestWorld.class);
        f.scene.world.packets = f.scene.packets;
        f.player.scene = f.scene;
        f.player.position = new Position();
        f.team = new TeamManager(f.player);
        set(f.player, Player.class, "teamManager", f.team);
        f.stamina = new TestStamina(f.player);
        set(f.player, Player.class, "staminaManager", f.stamina);
        set(f.player, Player.class, "satiationManager", new TestSatiation(f.player));
        set(f.player, Player.class, "energyManager", new TestEnergy(f.player));
        set(f.player, Player.class, "abilityManager", new AbilityManager(f.player));
        set(f.player, Player.class, "attackResults", new ArrayDeque<>());
        set(f.player, Player.class, "combatInvokeHandler", new InvokeHandler<>(PacketCombatInvocationsNotify.class));
        set(f.player, Player.class, "abilityInvokeHandler", new InvokeHandler<>(PacketAbilityInvocationsNotify.class));
        f.session = allocate(GameSession.class);
        set(f.session, GameSession.class, "player", f.player);
        f.player.setSession(f.session);
        f.entity = second(f);
        f.team.getActiveTeam().add(f.entity);
        f.team.setCurrentCharacterIndex(0);
        return f;
    }

    static TestEntity second(Fixture f) throws Exception {
        var entity = allocate(TestEntity.class);
        var avatar = allocate(TestAvatar.class);
        set(avatar, Avatar.class, "fightProperties", new Int2FloatOpenHashMap());
        set(avatar, Avatar.class, "avatarId", 10000032);
        set(avatar, Avatar.class, "guid", (long) (f.scene.entities.size() + 1));
        avatar.setOwner(f.player);
        var depot = allocate(AvatarSkillDepotData.class);
        set(depot, AvatarSkillDepotData.class, "elementType", ElementType.Fire);
        set(avatar, Avatar.class, "skillDepot", depot);
        set(entity, EntityAvatar.class, "avatar", avatar);
        set(entity, GameEntity.class, "scene", f.scene);
        set(entity, GameEntity.class, "globalAbilityValues", new ConcurrentHashMap<String, Float>());
        set(entity, GameEntity.class, "instancedModifiers", new ConcurrentHashMap<>());
        entity.position = new Position();
        entity.setId(f.scene.entities.size() + 1);
        entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 100);
        entity.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, 100);
        entity.setFightProperty(FightProperty.FIGHT_PROP_MAX_FIRE_ENERGY, 80);
        entity.setFightProperty(FightProperty.FIGHT_PROP_CHARGE_EFFICIENCY, 3);
        f.scene.entities.put(entity.getId(), entity);
        return entity;
    }

    static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }
    static void set(Object instance, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
    static Object get(Object instance, Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
    static class Fixture { TestPlayer player; TestEntity entity; TeamManager team; TestStamina stamina; TestScene scene; GameSession session; }
    static class TestAvatar extends Avatar { @Override public void save() {} }
    static class TestEntity extends EntityAvatar {
        Position position;
        TestEntity() { super((Avatar) null); }
        @Override public Position getPosition() { return position; }
        @Override public Position getRotation() { return position; }
        @Override public void checkIfDead() {}
        @Override public void runLuaCallbacks(EntityDamageEvent event) {}
    }
    static class TestPlayer extends Player {
        TestScene scene;
        Position position;
        @Override public Scene getScene() { return scene; }
        @Override public World getWorld() { return scene.world; }
        @Override public Position getPosition() { return position; }
        @Override public void sendPacket(BasePacket packet) { scene.packets.add(packet); }
    }
    static class TestWorld extends World {
        List<BasePacket> packets;
        TestWorld() { super((Player) null); }
        @Override public void broadcastPacket(BasePacket packet) { packets.add(packet); }
    }
    static class TestScene extends Scene {
        List<BasePacket> packets;
        Map<Integer, GameEntity> entities;
        List<Player> players;
        TestWorld world;
        int replacements;
        int attacks;
        TestScene() { super(null, null); }
        @Override public World getWorld() { return world; }
        @Override public List<Player> getPlayers() { return players; }
        @Override public GameEntity getEntityById(int id) { return entities.get(id); }
        @Override public void broadcastPacket(BasePacket packet) { packets.add(packet); }
        @Override public void replaceEntity(EntityAvatar old, EntityAvatar next) {
            replacements++;
            entities.remove(old.getId());
            entities.put(next.getId(), next);
        }
        @Override public void handleAttack(AttackResult attack) {
            attacks++;
            var entity = entities.get(attack.getDefenseId());
            if (entity != null) entity.damage(attack.getDamage(), attack.getAttackerId(), ElementType.None);
        }
    }
    static class ClockCombat extends HandlerCombatInvocationsNotify {
        long now = 1000;
        @Override protected long monotonicMillis() { return now; }
    }
    static class ClockStamina extends CostStaminaMixin {
        long now;
        @Override protected long monotonicMillis() { return now; }
    }
    static class TestEnergy extends EnergyManager {
        TestEnergy(Player player) { super(player); }
        @Override public void handleAttackHit(EvtBeingHitInfo hit) {}
    }
    static class CountingEnergy extends EnergyManager {
        int[] events;
        CountingEnergy(Player player, int[] events) { super(player); this.events = events; }
        @Override public void handleMonsterEnergyDrop(EntityMonster monster, float before, float after) { events[0]++; }
    }
    static class TestMonster extends EntityMonster {
        Int2FloatOpenHashMap props;
        TestMonster() { super(null, null, new Position(), new Position(), 1); }
        @Override public Int2FloatOpenHashMap getFightProperties() { return props; }
        @Override public void checkIfDead() {}
        @Override public void runLuaCallbacks(EntityDamageEvent event) {}
    }
    static class TestSatiation extends SatiationManager {
        TestSatiation(Player player) { super(player); }
        @Override public synchronized void removeSatiationDirectly(Avatar avatar, int value) {}
    }
    static class TestStamina extends StaminaManager {
        GameEntity killed;
        int spent;
        @Override public int updateStaminaRelative(GameSession session, Consumption consumption, boolean character) {
            spent += consumption.amount;
            return 0;
        }
        TestStamina(Player player) { super(player); }
        @Override public int getCurrentCharacterStamina() { return 0; }
        @Override public int getMaxCharacterStamina() { return 24000; }
        @Override public void killAvatar(GameSession session, GameEntity entity, PlayerDieType reason) { killed = entity; }
        @Override public void startSustainedStaminaHandler() {}
    }
}
