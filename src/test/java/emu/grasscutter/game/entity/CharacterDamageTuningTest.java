package emu.grasscutter.game.entity;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.UnknownFieldSet;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.config.ConfigContainer.GameOptions;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.managers.energy.EnergyManager;
import emu.grasscutter.game.player.InvokeHandler;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.props.ElementType;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.AttackResultOuterClass.AttackResult;
import emu.grasscutter.net.proto.CombatInvocationsNotifyOuterClass.CombatInvocationsNotify;
import emu.grasscutter.net.proto.CombatInvokeEntryOuterClass.CombatInvokeEntry;
import emu.grasscutter.net.proto.CombatTypeArgumentOuterClass.CombatTypeArgument;
import emu.grasscutter.net.proto.EvtBeingHitInfoOuterClass.EvtBeingHitInfo;
import emu.grasscutter.net.proto.ForwardTypeOuterClass.ForwardType;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.recv.HandlerCombatInvocationsNotify;
import emu.grasscutter.server.packet.send.PacketCombatInvocationsNotify;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
final class CharacterDamageTuningTest {
    private RecordingScene scene;
    private EntityAvatar keqing;
    private EntityAvatar fischl;
    private RecordingMonster monster;

    @BeforeEach
    void setup() throws Exception {
        scene = allocate(RecordingScene.class);
        scene.entities = new HashMap<>();
        keqing = avatar(1, 10000042);
        fischl = avatar(2, 10000031);
        monster = allocate(RecordingMonster.class);
        put(monster, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATK01", "ChargeAttack", "ElementalArt", "ElementalBurst", "Aggravate"})
    void scalesKeqingFinalElectroHitAndPreservesAllOtherFields(String animation) {
        var unknown = UnknownFieldSet.newBuilder()
                .addField(90001, UnknownFieldSet.Field.newBuilder().addVarint(456).build()).build();
        var original = hit(keqing, 4, 100f).toBuilder().setUnknownFields(unknown)
                .setAttackResult(hit(keqing, 4, 100f).getAttackResult().toBuilder()
                        .setAnimEventId(animation).setUnknownFields(unknown)).build();
        var tuned = CharacterDamageTuning.apply(scene, original, 1.5f);
        assertEquals(150f, tuned.getAttackResult().getDamage());
        assertEquals(original, tuned.toBuilder()
                .setAttackResult(tuned.getAttackResult().toBuilder().setDamage(100f)).build());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 5, 7, 8, 255})
    void leavesOtherElementsUnchanged(int element) {
        var original = hit(keqing, element, 100f);
        assertSame(original, CharacterDamageTuning.apply(scene, original, 1.5f));
    }

    @Test
    void leavesFischlAndDilucUnchanged() throws Exception {
        for (var actor : new EntityAvatar[] {fischl, avatar(4, 10000016)}) {
            var original = hit(actor, 4, 100f);
            assertSame(original, CharacterDamageTuning.apply(scene, original, 1.5f));
        }
    }

    @Test
    void resolvesNestedKeqingGadgetsButNotFischlsOz() throws Exception {
        var nested = gadget(6, gadget(5, keqing.getId(), 0).getId(), 0);
        assertEquals(150f, CharacterDamageTuning.apply(scene, hit(nested, 4, 100f), 1.5f)
                .getAttackResult().getDamage());
        var oz = gadget(7, fischl.getId(), keqing.getId());
        var original = hit(oz, 4, 100f);
        assertSame(original, CharacterDamageTuning.apply(scene, original, 1.5f));
    }

    @Test
    void resolvesOriginalOwnerOnlyWhenImmediateOwnerIsMissing() throws Exception {
        var projectile = gadget(5, 999, keqing.getId());
        assertEquals(150f, CharacterDamageTuning.apply(scene, hit(projectile, 4, 100f), 1.5f)
                .getAttackResult().getDamage());
    }

    @Test
    void refusesMissingAndCyclicOwnership() throws Exception {
        var missing = gadget(5, 999, 0);
        gadget(6, 7, 0);
        var cycle = gadget(7, 6, 0);
        for (var actor : new GameEntity[] {missing, cycle}) {
            var original = hit(actor, 4, 100f);
            assertSame(original, CharacterDamageTuning.apply(scene, original, 1.5f));
        }
        var absent = hit(keqing, 4, 100f).toBuilder()
                .setAttackResult(hit(keqing, 4, 100f).getAttackResult().toBuilder().setAttackerId(999)).build();
        assertSame(absent, CharacterDamageTuning.apply(scene, absent, 1.5f));
    }

    @Test
    void refusesAvatarGadgetAndMissingTargets() throws Exception {
        var object = gadget(5, keqing.getId(), 0);
        for (int id : new int[] {fischl.getId(), object.getId(), 999}) {
            var original = hit(keqing, 4, 100f).toBuilder()
                    .setAttackResult(hit(keqing, 4, 100f).getAttackResult().toBuilder().setDefenseId(id)).build();
            assertSame(original, CharacterDamageTuning.apply(scene, original, 1.5f));
        }
    }

    @Test
    void rejectsInvalidDamageMultipliersAndOverflow() {
        var original = hit(keqing, 4, 100f);
        for (float multiplier : new float[] {0, -1, 1, Float.NaN, Float.POSITIVE_INFINITY})
            assertSame(original, CharacterDamageTuning.apply(scene, original, multiplier));
        for (float damage : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE}) {
            var invalid = hit(keqing, 4, damage);
            assertSame(invalid, CharacterDamageTuning.apply(scene, invalid, 1.5f));
        }
        assertSame(original, CharacterDamageTuning.apply(null, original, 1.5f));
        var empty = EvtBeingHitInfo.getDefaultInstance();
        assertSame(empty, CharacterDamageTuning.apply(scene, empty, 1.5f));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 7, 8})
    void scalesDilucPhysicalAndElementalHitsWithoutStackingKeqingMultiplier(int element) throws Exception {
        var diluc = avatar(4, 10000016);
        var original = hit(diluc, element, 100f);
        var tuned = CharacterDamageTuning.apply(scene, original, 1.5f, 1.25f);
        assertEquals(125f, tuned.getAttackResult().getDamage());
        assertEquals(original, tuned.toBuilder()
                .setAttackResult(tuned.getAttackResult().toBuilder().setDamage(100f)).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATK01", "ChargeAttack", "HighPlunge", "ElementalArt", "ElementalBurst"})
    void scalesEveryDilucAttackIncludingAlreadyAmplifiedPlunges(String animation) throws Exception {
        var diluc = avatar(4, 10000016);
        var original = hit(diluc, 1, 1000f).toBuilder()
                .setAttackResult(hit(diluc, 1, 1000f).getAttackResult().toBuilder()
                        .setAnimEventId(animation)).build();
        assertEquals(1250f, CharacterDamageTuning.apply(scene, original, 1.5f, 1.25f)
                .getAttackResult().getDamage());
    }

    @Test
    void resolvesDilucBurstGadgetsWithoutBuffingTeammates() throws Exception {
        var diluc = avatar(4, 10000016);
        var phoenix = gadget(6, gadget(5, diluc.getId(), 0).getId(), 0);
        assertEquals(125f, CharacterDamageTuning.apply(scene, hit(phoenix, 1, 100f), 1.5f, 1.25f)
                .getAttackResult().getDamage());
        for (var actor : new EntityAvatar[] {fischl, avatar(7, 10000093), avatar(8, 10000032)}) {
            var original = hit(actor, 1, 100f);
            assertSame(original, CharacterDamageTuning.apply(scene, original, 1.5f, 1.25f));
        }
        var oz = gadget(9, fischl.getId(), diluc.getId());
        var original = hit(oz, 4, 100f);
        assertSame(original, CharacterDamageTuning.apply(scene, original, 1.5f, 1.25f));
    }

    @Test
    void dilucAndKeqingSettingsAreIndependentAndRejectInvalidValues() throws Exception {
        var diluc = avatar(4, 10000016);
        assertEquals(125f, CharacterDamageTuning.apply(scene, hit(diluc, 1, 100f), Float.NaN, 1.25f)
                .getAttackResult().getDamage());
        assertEquals(150f, CharacterDamageTuning.apply(scene, hit(keqing, 4, 100f), 1.5f, Float.NaN)
                .getAttackResult().getDamage());
        for (float multiplier : new float[] {0, -1, 1, Float.NaN, Float.POSITIVE_INFINITY}) {
            var original = hit(diluc, 1, 100f);
            assertSame(original, CharacterDamageTuning.apply(scene, original, 1.5f, multiplier));
        }
        var overflow = hit(diluc, 1, Float.MAX_VALUE);
        assertSame(overflow, CharacterDamageTuning.apply(scene, overflow, 1.5f, 1.25f));
    }

    @Test
    void oldConfigDefaultsToUnchangedDamageAndConfiguredValueLoads() {
        var gson = new Gson();
        assertEquals(1f, gson.fromJson("{}", GameOptions.class).keqingElectroDamageMultiplier);
        assertEquals(1.5f, gson.fromJson("{\"keqingElectroDamageMultiplier\":1.5}", GameOptions.class)
                .keqingElectroDamageMultiplier);
        assertEquals(1f, gson.fromJson("{}", GameOptions.class).dilucDamageMultiplier);
        var both = gson.fromJson("{\"keqingElectroDamageMultiplier\":1.5,\"dilucDamageMultiplier\":1.25}",
                GameOptions.class);
        assertEquals(1.5f, both.keqingElectroDamageMultiplier);
        assertEquals(1.25f, both.dilucDamageMultiplier);
    }

    @Test
    void deployedClassesComeFromExpectedJarWhenRequested() throws Exception {
        String expected = System.getProperty("combatJar");
        if (expected == null) return;
        for (var type : new Class<?>[] {CharacterDamageTuning.class, GameOptions.class,
                HandlerCombatInvocationsNotify.class}) {
            assertEquals(java.nio.file.Path.of(expected).toRealPath(),
                    java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {10000042, 10000031, 10000016})
    void actualHandlerQueuesAndForwardsTheSameDamageThenSceneAppliesItOnce(int avatarId) throws Exception {
        var actor = avatar(8, avatarId);
        var player = allocate(Player.class);
        var team = new TeamManager();
        team.getActiveTeam().add(actor);
        var energy = allocate(RecordingEnergy.class);
        var invoke = new RecordingInvoke();
        set(player, Player.class, "scene", scene);
        set(player, Player.class, "teamManager", team);
        set(player, Player.class, "energyManager", energy);
        set(player, Player.class, "attackResults", new ArrayDeque<AttackResult>());
        set(player, Player.class, "combatInvokeHandler", invoke);
        var session = allocate(GameSession.class);
        set(session, GameSession.class, "player", player);
        var original = hit(actor, avatarId == 10000016 ? 1 : 4, 100f);
        var entry = CombatInvokeEntry.newBuilder()
                .setArgumentType(CombatTypeArgument.CombatTypeArgument_COMBAT_EVT_BEING_HIT)
                .setForwardType(ForwardType.ForwardType_FORWARD_TO_ALL_EXCEPT_CUR)
                .setCombatData(original.toByteString()).build();
        var packet = CombatInvocationsNotify.newBuilder().addInvokeList(entry).build();
        float previous = GAME_OPTIONS.keqingElectroDamageMultiplier;
        float previousDiluc = GAME_OPTIONS.dilucDamageMultiplier;
        try {
            GAME_OPTIONS.keqingElectroDamageMultiplier = 1.5f;
            GAME_OPTIONS.dilucDamageMultiplier = 1.25f;
            new HandlerCombatInvocationsNotify().handle(session, new byte[0], packet.toByteArray());
        } finally {
            GAME_OPTIONS.keqingElectroDamageMultiplier = previous;
            GAME_OPTIONS.dilucDamageMultiplier = previousDiluc;
        }
        float expected = avatarId == 10000042 ? 150f : avatarId == 10000016 ? 125f : 100f;
        assertEquals(1, player.getAttackResults().size());
        var queued = player.getAttackResults().remove();
        var forwarded = EvtBeingHitInfo.parseFrom(invoke.entry.getCombatData());
        assertEquals(expected, queued.getDamage());
        assertEquals(queued, forwarded.getAttackResult());
        assertEquals(forwarded, energy.hit);
        assertEquals(avatarId != 10000031 ? ForwardType.ForwardType_FORWARD_TO_ALL
                : ForwardType.ForwardType_FORWARD_TO_ALL_EXCEPT_CUR, invoke.entry.getForwardType());
        scene.handleAttack(queued);
        assertEquals(expected, monster.damage);
        assertEquals(1, monster.hits);
    }

    private EvtBeingHitInfo hit(GameEntity actor, int element, float damage) {
        return EvtBeingHitInfo.newBuilder().setAttackResult(AttackResult.newBuilder()
                .setAttackerId(actor.getId()).setDefenseId(monster.getId())
                .setElementType(element).setDamage(damage)).build();
    }

    private EntityAvatar avatar(int id, int avatarId) throws Exception {
        var data = new Avatar();
        set(data, Avatar.class, "avatarId", avatarId);
        var entity = allocate(EntityAvatar.class);
        set(entity, EntityAvatar.class, "avatar", data);
        return put(entity, id);
    }

    private EntityClientGadget gadget(int id, int owner, int originalOwner) throws Exception {
        var entity = allocate(EntityClientGadget.class);
        set(entity, EntityClientGadget.class, "ownerEntityId", owner);
        set(entity, EntityClientGadget.class, "originalOwnerEntityId", originalOwner);
        return put(entity, id);
    }

    private <T extends GameEntity> T put(T entity, int id) throws Exception {
        entity.setId(id);
        set(entity, GameEntity.class, "scene", scene);
        scene.entities.put(id, entity);
        return entity;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void set(Object instance, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static final class RecordingScene extends Scene {
        private Map<Integer, GameEntity> entities;
        private RecordingScene() { super(null, null); }
        @Override public GameEntity getEntityById(int id) { return entities.get(id); }
    }

    private static final class RecordingMonster extends EntityMonster {
        private float damage;
        private int hits;
        private RecordingMonster() { super(null, null, new Position(), new Position(), 90); }
        @Override public void damage(float amount, int attacker, ElementType element) {
            damage += amount;
            hits++;
        }
    }

    private static final class RecordingEnergy extends EnergyManager {
        private EvtBeingHitInfo hit;
        private RecordingEnergy() { super(null); }
        @Override public void handleAttackHit(EvtBeingHitInfo value) { hit = value; }
    }

    private static final class RecordingInvoke extends InvokeHandler<CombatInvokeEntry> {
        private CombatInvokeEntry entry;
        private RecordingInvoke() { super(PacketCombatInvocationsNotify.class); }
        @Override public synchronized void addEntry(ForwardType type, CombatInvokeEntry value) { entry = value; }
        @Override public synchronized void update(Player player) {}
    }
}
