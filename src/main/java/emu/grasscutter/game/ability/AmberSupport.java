package emu.grasscutter.game.ability;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.data.common.DynamicFloat;
import emu.grasscutter.game.ability.actions.ActionHealHP;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.AbilityAppliedAbilityOuterClass.AbilityAppliedAbility;
import emu.grasscutter.net.proto.AbilityMetaAddAbilityOuterClass.AbilityMetaAddAbility;
import emu.grasscutter.net.proto.AbilityMetaReInitOverrideMapOuterClass.AbilityMetaReInitOverrideMap;
import emu.grasscutter.net.proto.AbilityInvokeArgumentOuterClass.AbilityInvokeArgument;
import emu.grasscutter.net.proto.AbilityInvokeEntryHeadOuterClass.AbilityInvokeEntryHead;
import emu.grasscutter.net.proto.AbilityInvokeEntryOuterClass.AbilityInvokeEntry;
import emu.grasscutter.net.proto.AbilityScalarValueEntryOuterClass.AbilityScalarValueEntry;
import emu.grasscutter.net.proto.AbilityStringOuterClass.AbilityString;
import emu.grasscutter.net.proto.ForwardTypeOuterClass.ForwardType;
import emu.grasscutter.server.packet.send.PacketAbilityInvocationsNotify;
import emu.grasscutter.utils.Utils;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/** Amber-only burst healing and overrides for her existing C6 ATK modifier. */
public final class AmberSupport {
    static final int AVATAR_ID = 10000021;
    static final int BURST_ID = 10017;
    static final String BURST_ABILITY = "Avatar_Ambor_ArrowRain";
    static final String C6_ATTACK = "Ambor_Talent_216_P2_AttackUp";
    static final int INTERVAL_SECONDS = 2;
    static final int TICKS = 6;
    private static final long DUPLICATE_CAST_MS = 1500;
    private static final ActionHealHP HEAL = new ActionHealHP();
    private static final Engine ENGINE = new Engine(
            () -> System.nanoTime() / 1_000_000,
            uid -> Grasscutter.getGameServer().getPlayerByUid(uid),
            new Tasks() {
                public int repeat(Runnable task, int period, int delay) {
                    return Grasscutter.getGameServer().getScheduler()
                            .scheduleDelayedRepeatingTask(task, period, delay);
                }
                public void cancel(int id) {
                    Grasscutter.getGameServer().getScheduler().cancelTask(id);
                }
            });

    private AmberSupport() {}

    public static void onConfirmedBurst(Player player, EntityAvatar caster, Ability ability, int skillId) {
        ENGINE.cast(player, caster, ability, skillId, GAME_OPTIONS.amberBurstHealHpRatio);
    }

    public static void clearPlayerState(Player player) {
        ENGINE.clear(player);
    }

    public static void clearEntityState(GameEntity entity) {
        if (entity instanceof EntityAvatar avatar) ENGINE.clearCaster(avatar.getPlayer(), avatar.getId());
    }

    /** An absolute override, never multiplied into a value already overridden by the client. */
    static Float c6Attack(Avatar avatar, float multiplier) {
        if (avatar == null || avatar.getAvatarId() != AVATAR_ID
                || !avatar.getTalentIdList().contains(216)
                || !Float.isFinite(multiplier) || multiplier <= 0f || multiplier == 1f) return null;
        var data = GameData.getAvatarTalentDataMap().get(216);
        if (data == null || data.getParamList() == null || data.getParamList().length < 2) return null;
        float base = data.getParamList()[1];
        float value = base * multiplier;
        return Float.isFinite(base) && base >= 0f && Float.isFinite(value) && value >= 0f ? value : null;
    }

    private static Float c6Attack(Ability ability) {
        if (ability == null || ability.getData() == null
                || !BURST_ABILITY.equals(ability.getData().abilityName)) return null;
        var caster = ability.getCasterEntity();
        return caster == null ? null : c6Attack(caster.getAvatar(), GAME_OPTIONS.amberC6AttackMultiplier);
    }

    public static void applyC6Special(Ability ability) {
        Float value = c6Attack(ability);
        if (value != null) ability.getAbilitySpecials().put(C6_ATTACK, value.floatValue());
    }

    public static AbilityAppliedAbility tuneAppliedAbility(Avatar avatar, AbilityAppliedAbility original) {
        if (!matches(original.getAbilityName(), BURST_ABILITY)) return original;
        Float value = c6Attack(avatar, GAME_OPTIONS.amberC6AttackMultiplier);
        if (value == null) return original;
        var builder = original.toBuilder().clearOverrideMap();
        for (var entry : original.getOverrideMapList()) {
            if (!matches(entry.getKey(), C6_ATTACK)) builder.addOverrideMap(entry);
        }
        return builder.addOverrideMap(attackEntry(value)).build();
    }

    private static boolean matches(AbilityString key, String name) {
        return !key.getStr().isEmpty() ? key.getStr().equals(name)
                : key.hasHash() && key.getHash() == Utils.abilityHash(name);
    }

    /** Canonicalize before processing AND forwarding, so observers cannot restore the old value. */
    public static AbilityInvokeEntry tuneInvoke(Player player, AbilityInvokeEntry original)
            throws InvalidProtocolBufferException {
        if (player == null || player.getScene() == null) return original;
        var entity = player.getScene().getEntityById(original.getEntityId());
        if (entity == null) return original;
        ByteString data = original.getAbilityData();
        ByteString tuned = data;
        if (original.getArgumentType() == AbilityInvokeArgument.AbilityInvokeArgument_ABILITY_META_ADD_NEW_ABILITY
                && entity instanceof EntityAvatar avatar) {
            var add = AbilityMetaAddAbility.parseFrom(data);
            var applied = tuneAppliedAbility(avatar.getAvatar(), add.getAbility());
            if (applied != add.getAbility()) tuned = add.toBuilder().setAbility(applied).build().toByteString();
        } else {
            int index = original.getHead().getInstancedAbilityId() - 1;
            if (index < 0 || index >= entity.getInstancedAbilities().size()) return original;
            var ability = entity.getInstancedAbilities().get(index);
            Float value = c6Attack(ability);
            if (value == null) return original;
            switch (original.getArgumentType()) {
                case AbilityInvokeArgument_ABILITY_META_OVERRIDE_PARAM -> {
                    var entry = AbilityScalarValueEntry.parseFrom(data);
                    if (matches(entry.getKey(), C6_ATTACK) && Float.compare(entry.getFloatValue(), value) != 0)
                        tuned = entry.toBuilder().setFloatValue(value).build().toByteString();
                }
                case AbilityInvokeArgument_ABILITY_META_REINIT_OVERRIDEMAP -> {
                    var reinit = AbilityMetaReInitOverrideMap.parseFrom(data);
                    var builder = reinit.toBuilder();
                    for (int i = 0; i < reinit.getOverrideMapCount(); i++) {
                        var entry = reinit.getOverrideMap(i);
                        if (matches(entry.getKey(), C6_ATTACK) && Float.compare(entry.getFloatValue(), value) != 0)
                            builder.setOverrideMap(i, entry.toBuilder().setFloatValue(value));
                    }
                    tuned = builder.build().toByteString();
                }
                default -> { return original; }
            }
        }
        return data.equals(tuned) ? original : original.toBuilder().setAbilityData(tuned)
                .setForwardType(ForwardType.ForwardType_FORWARD_TO_ALL).build();
    }

    /** Reassert only when the client has just initialized/replaced the native C6 value. */
    public static void syncC6Special(GameEntity entity, Ability ability, int instanceId, boolean force) {
        Float value = c6Attack(ability);
        if (value == null || entity == null || entity.getScene() == null || instanceId <= 0) return;
        float previous = ability.getAbilitySpecials().getOrDefault(C6_ATTACK, Float.NaN);
        ability.getAbilitySpecials().put(C6_ATTACK, value.floatValue());
        if (!force && Float.compare(previous, value) == 0) return;
        var invoke = AbilityInvokeEntry.newBuilder()
                .setEntityId(entity.getId())
                .setForwardType(ForwardType.ForwardType_FORWARD_TO_ALL)
                .setArgumentType(AbilityInvokeArgument.AbilityInvokeArgument_ABILITY_META_OVERRIDE_PARAM)
                .setHead(AbilityInvokeEntryHead.newBuilder().setInstancedAbilityId(instanceId))
                .setAbilityData(attackEntry(value).toByteString()).build();
        entity.getScene().broadcastPacket(new PacketAbilityInvocationsNotify(java.util.List.of(invoke)));
    }

    private static AbilityScalarValueEntry attackEntry(float value) {
        return AbilityScalarValueEntry.newBuilder()
                .setKey(AbilityString.newBuilder().setStr(C6_ATTACK).setHash(Utils.abilityHash(C6_ATTACK)))
                .setFloatValue(value).build();
    }

    interface Tasks {
        int repeat(Runnable task, int period, int delay);
        void cancel(int id);
    }

    /** Serializes refresh/tick/cleanup so an expired callback cannot heal or erase a newer cast. */
    static final class Engine {
        private final LongSupplier clock;
        private final IntFunction<Player> players;
        private final Tasks tasks;
        private final Map<Integer, Run> runs = new HashMap<>();

        Engine(LongSupplier clock, IntFunction<Player> players, Tasks tasks) {
            this.clock = clock;
            this.players = players;
            this.tasks = tasks;
        }

        synchronized void cast(Player player, EntityAvatar caster, Ability ability, int skillId, float ratio) {
            if (player == null || caster == null || caster.getAvatar() == null || ability == null
                    || ability.getOwner() != caster || skillId != BURST_ID
                    || caster.getAvatar().getAvatarId() != AVATAR_ID || !caster.isAlive()
                    || player.getScene() == null || caster.getScene() != player.getScene()
                    || player.getTeamManager() == null
                    || !player.getTeamManager().getActiveTeam().contains(caster)
                    || !Float.isFinite(ratio) || ratio <= 0f || ratio > 1f) return;
            float hp = caster.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
            float bonus = caster.getFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD);
            float base = hp * ratio * (1f + bonus);
            if (!Float.isFinite(hp) || hp <= 0f || !Float.isFinite(bonus)
                    || !Float.isFinite(base) || base <= 0f) return;
            long now = clock.getAsLong();
            Run previous = runs.get(player.getUid());
            if (previous != null && previous.player == player && previous.caster == caster
                    && now - previous.castAt < DUPLICATE_CAST_MS) return;
            if (previous != null) stop(previous);
            Run run = new Run(player, caster, ability, base, now, party(player));
            runs.put(player.getUid(), run);
            try {
                // Scheduler periods are server ticks (currently 200ms), not seconds.
                // Poll each tick; the monotonic clock below controls real healing intervals.
                run.taskId = tasks.repeat(() -> tick(run), 1, 1);
            } catch (RuntimeException error) {
                runs.remove(player.getUid(), run);
                throw error;
            }
        }

        private synchronized void tick(Run run) {
            if (runs.get(run.player.getUid()) != run) return;
            Player live = players.apply(run.player.getUid());
            if (live != run.player || live.getScene() != run.scene || !run.caster.isAlive()
                    || !live.getTeamManager().getActiveTeam().contains(run.caster)
                    || !party(live).equals(run.party)) {
                stop(run);
                return;
            }
            long slot = (clock.getAsLong() - run.castAt) / (INTERVAL_SECONDS * 1000L);
            if (slot <= run.lastTick) return;
            if (slot > TICKS) {
                stop(run);
                return;
            }
            run.lastTick = (int) slot; // Skip missed slots after a stall; never burst-heal to catch up.
            try {
                var seen = new HashSet<Long>();
                for (var member : new ArrayList<>(live.getTeamManager().getActiveTeam())) {
                    if (member == null || member.getAvatar() == null || member.getScene() != run.scene
                            || !seen.add(member.getAvatar().getGuid()) || !member.isAlive()
                            || member.isRestrictedFromHealing() || member.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP) <= 0f) continue;
                    // Arlecchino cannot receive teammates' healing in combat.
                    if (member.getAvatar().getAvatarId() == 10000096
                            && member.getGlobalAbilityValues().getOrDefault("_ABILITY_Avatar_ForbidFoodHeal", 0f) > 0f)
                        continue;
                    float bonus = member.getFightProperty(FightProperty.FIGHT_PROP_HEALED_ADD);
                    float amount = run.base * (1f + bonus);
                    if (!Float.isFinite(bonus) || !Float.isFinite(amount) || amount <= 0f) continue;
                    var action = new AbilityModifierAction();
                    action.type = AbilityModifierAction.Type.HealHP;
                    action.amount = new DynamicFloat(amount);
                    action.ignoreAbilityProperty = true; // Bonuses already applied, once each.
                    // Reuse native debt conversion, HP clamping, HP/heal notifications and onHeal hooks.
                    HEAL.execute(run.ability, action, ByteString.EMPTY, member);
                }
            } finally {
                if (run.lastTick == TICKS) stop(run);
            }
        }

        synchronized void clear(Player player) {
            if (player == null) return;
            Run run = runs.get(player.getUid());
            if (run != null && run.player == player) stop(run);
        }

        synchronized void clearCaster(Player player, int entityId) {
            if (player == null) return;
            Run run = runs.get(player.getUid());
            if (run != null && run.player == player && run.caster.getId() == entityId) stop(run);
        }

        private void stop(Run run) {
            runs.remove(run.player.getUid(), run);
            if (run.taskId >= 0) tasks.cancel(run.taskId);
        }

        private static Set<Long> party(Player player) {
            var result = new HashSet<Long>();
            for (var member : new ArrayList<>(player.getTeamManager().getActiveTeam())) {
                if (member != null && member.getAvatar() != null) result.add(member.getAvatar().getGuid());
            }
            return result;
        }
    }

    private static final class Run {
        final Player player;
        final EntityAvatar caster;
        final Ability ability;
        final Scene scene;
        final float base;
        final long castAt;
        final Set<Long> party;
        int taskId = -1;
        int lastTick;

        Run(Player player, EntityAvatar caster, Ability ability, float base, long castAt, Set<Long> party) {
            this.player = player;
            this.caster = caster;
            this.ability = ability;
            this.scene = player.getScene();
            this.base = base;
            this.castAt = castAt;
            this.party = party;
        }
    }
}
