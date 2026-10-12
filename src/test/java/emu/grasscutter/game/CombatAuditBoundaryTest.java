package emu.grasscutter.game;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.game.CombatAuditTest.*;
import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import com.google.protobuf.ByteString;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.binout.*;
import emu.grasscutter.data.common.DynamicFloat;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.data.excels.avatar.AvatarSkillDepotData;
import emu.grasscutter.data.excels.avatar.AvatarSkillData;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.ability.actions.ActionHealHP;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.CombatDamageBatch;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.props.*;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.CombatInvocationsNotifyOuterClass.CombatInvocationsNotify;
import emu.grasscutter.net.proto.CombatInvokeEntryOuterClass.CombatInvokeEntry;
import emu.grasscutter.net.proto.CombatTypeArgumentOuterClass.CombatTypeArgument;
import emu.grasscutter.net.proto.EvtBeingHitInfoOuterClass.EvtBeingHitInfo;
import emu.grasscutter.net.proto.AttackResultOuterClass.AttackResult;
import emu.grasscutter.net.proto.MotionStateOuterClass.MotionState;
import emu.grasscutter.net.proto.UnionCmdNotifyOuterClass.UnionCmdNotify;
import emu.grasscutter.net.proto.UnionCmdOuterClass.UnionCmd;
import emu.grasscutter.plugin.PluginManager;
import emu.grasscutter.server.event.*;
import emu.grasscutter.server.event.player.PlayerSwitchAvatarEvent;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
class CombatAuditBoundaryTest {
    @Test void fallSamplesExpireAndDoNotFollowSwitchedAvatarOrScene() throws Exception {
        var f = fixture();
        var handler = new ClockCombat();
        movement(handler, f, MotionState.MotionState_MOTION_LAND_SPEED, -27);
        handler.now += 201;
        movement(handler, f, MotionState.MotionState_MOTION_FALL_ON_GROUND, 0);
        assertEquals(100, hp(f.entity));
        movement(handler, f, MotionState.MotionState_MOTION_LAND_SPEED, -27);
        f.entity = second(f);
        f.team.getActiveTeam().add(f.entity);
        f.team.setCurrentCharacterIndex(1);
        movement(handler, f, MotionState.MotionState_MOTION_FALL_ON_GROUND, 0);
        assertEquals(100, hp(f.entity));
        movement(handler, f, MotionState.MotionState_MOTION_LAND_SPEED, -27);
        var destination = fixture().scene;
        destination.entities.put(f.entity.getId(), f.entity);
        f.player.scene = destination;
        set(f.entity, GameEntity.class, "scene", destination);
        movement(handler, f, MotionState.MotionState_MOTION_FALL_ON_GROUND, 0);
        assertEquals(100, hp(f.entity));
    }

    @Test void invincibilityAndLimboRemainSeparateDamageInterceptions() throws Exception {
        var f = fixture();
        f.entity.setModifierInvincible(true);
        f.entity.damage(101, 0, ElementType.None);
        f.entity.loseHpByAbility(101, false);
        assertEquals(100, hp(f.entity));
        f.entity.setModifierInvincible(false);
        set(f.entity, GameEntity.class, "limbo", true);
        set(f.entity, GameEntity.class, "limboHpThreshold", .1f);
        f.entity.damage(101, 0, ElementType.None);
        assertEquals(1, hp(f.entity));
    }

    @Test void energyDisabledAndSpecialBarsRemainUnchangedForBothRefundPaths() throws Exception {
        boolean old = GAME_OPTIONS.energyUsage;
        try {
            for (boolean mixin : new boolean[]{false, true}) {
                var f = fixture();
                f.entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_FIRE_ENERGY, 79);
                GAME_OPTIONS.energyUsage = false;
                refund(f, mixin);
                assertEquals(79, f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_FIRE_ENERGY));
                assertTrue(f.scene.packets.isEmpty());
                GAME_OPTIONS.energyUsage = true;
                var special = new AvatarSkillData();
                special.specialEnergyMin = 100;
                set(f.entity.getAvatar().getSkillDepot(), AvatarSkillDepotData.class, "energySkillData", special);
                f.entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_SPECIAL_ENERGY, 40);
                refund(f, mixin);
                assertEquals(79, f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_FIRE_ENERGY));
                assertEquals(40, f.entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_SPECIAL_ENERGY));
                assertTrue(f.scene.packets.isEmpty());
            }
        } finally { GAME_OPTIONS.energyUsage = old; }
    }

    @Test void staminaCharges100500501600msCapsRestartAndPreservesExemption() throws Exception {
        for (long elapsed : new long[]{100, 500, 501, 600, 5000}) {
            var f = fixture();
            var ability = testAbility(f);
            var data = new AbilityMixinData();
            data.costStaminaDelta = new DynamicFloat(1);
            var handler = new ClockStamina();
            handler.now = 1000;
            handler.execute(ability, data, ByteString.EMPTY, f.entity);
            handler.now += elapsed;
            handler.execute(ability, data, ByteString.EMPTY, f.entity);
            assertEquals(-(int) (Math.min(1000, elapsed) / 10), f.stamina.spent);
            f.entity.getGlobalAbilityValues().put("Audit_IsNyxState", 1f);
            handler.now += 600;
            int paid = f.stamina.spent;
            handler.execute(ability, data, ByteString.EMPTY, f.entity);
            assertEquals(paid, f.stamina.spent);
            f.entity.getGlobalAbilityValues().clear();
            handler.execute(ability, data, ByteString.EMPTY, f.entity);
            assertEquals(paid, f.stamina.spent, "reactivation initializes a new timing window");
        }
    }

    @Test void dynamicMixedAttackHealingAndRatioOnlyHealingHaveSingleFlatTerm() throws Exception {
        var f = healingFixture();
        var ability = testAbility(f);
        ability.getAbilitySpecials().put("AuditFlat", 1000f);
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_ATTACK, 5000);
        var action = heal();
        action.ignoreAbilityProperty = true;
        action.amount = new DynamicFloat("AuditFlat");
        action.amountByCasterAttackRatio = new DynamicFloat(.2f);
        assertTrue(new ActionHealHP().execute(ability, action, ByteString.EMPTY, f.entity));
        assertEquals(3000, hp(f.entity));
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 1000);
        action.amount = DynamicFloat.ZERO;
        action.amountByCasterAttackRatio = DynamicFloat.ZERO;
        action.amountByCasterMaxHPRatio = new DynamicFloat(.1f);
        assertTrue(new ActionHealHP().execute(ability, action, ByteString.EMPTY, f.entity));
        assertEquals(2000, hp(f.entity));
    }

    @Test void gadgetUsesOriginalCasterNotCurrentOwnerOrRecipientBonus() throws Exception {
        var f = healingFixture();
        var caster = second(f);
        highHp(caster);
        caster.setFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD, .5f);
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD, 2f);
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_HEALED_ADD, .25f);
        var gadget = allocate(EntityClientGadget.class);
        gadget.setId(900);
        set(gadget, GameEntity.class, "scene", f.scene);
        set(gadget, EntityClientGadget.class, "originalOwnerEntityId", caster.getId());
        set(gadget, EntityClientGadget.class, "ownerEntityId", f.entity.getId());
        var ability = testAbility(f);
        set(ability, Ability.class, "owner", gadget);
        assertTrue(new ActionHealHP().execute(ability, heal(), ByteString.EMPTY, f.entity));
        assertEquals(2750, hp(f.entity));
    }

    @Test void actualModifierInvocationKeepsCasterAbilityWhenHolderIsRecipient() throws Exception {
        var f = healingFixture();
        var caster = second(f);
        highHp(caster);
        caster.setFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD, .5f);
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD, 2f);
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_HEALED_ADD, .25f);
        var ability = testAbility(f);
        set(ability, Ability.class, "owner", caster);
        ability.getData().abilityName = "Audit_Modifier_Heal";
        var action = heal();
        action.type = AbilityModifier.AbilityModifierAction.Type.HealHP;
        ability.getData().localIdToAction.put(1, action);
        f.entity.getInstancedModifiers().put(7,
                new emu.grasscutter.game.ability.AbilityModifierController(ability, ability.getData(), null));
        var field = emu.grasscutter.game.ability.AbilityManager.class.getDeclaredField("actionHandlers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var handlers = (Map<AbilityModifier.AbilityModifierAction.Type,
                emu.grasscutter.game.ability.actions.AbilityActionHandler>) field.get(null);
        var complete = new java.util.concurrent.CompletableFuture<Boolean>();
        var handler = new emu.grasscutter.game.ability.actions.AbilityActionHandler() {
            @Override public boolean execute(Ability used, AbilityModifier.AbilityModifierAction data,
                    ByteString bytes, GameEntity target) {
                try {
                    assertSame(ability, used);
                    assertSame(f.entity, target);
                    boolean result = new ActionHealHP().execute(used, data, bytes, target);
                    complete.complete(result);
                    return result;
                } catch (Throwable failure) {
                    complete.completeExceptionally(failure);
                    return false;
                }
            }
        };
        var old = handlers.put(action.type, handler);
        try {
            var invoke = emu.grasscutter.net.proto.AbilityInvokeEntryOuterClass.AbilityInvokeEntry.newBuilder()
                    .setEntityId(f.entity.getId())
                    .setHead(emu.grasscutter.net.proto.AbilityInvokeEntryHeadOuterClass.AbilityInvokeEntryHead.newBuilder()
                            .setInstancedModifierId(7).setLocalId(1).setTargetId(f.entity.getId())).build();
            var method = emu.grasscutter.game.ability.AbilityManager.class.getDeclaredMethod(
                    "handleServerInvoke", emu.grasscutter.net.proto.AbilityInvokeEntryOuterClass.AbilityInvokeEntry.class);
            method.setAccessible(true);
            method.invoke(f.player.getAbilityManager(), invoke);
            assertTrue(complete.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2750, hp(f.entity));
        } finally {
            if (old == null) handlers.remove(action.type); else handlers.put(action.type, old);
        }
    }

    @Test void namedCasterHeldByRecipientAndRetargetUseCorrectBonusOwners() throws Exception {
        var f = healingFixture();
        var caster = second(f);
        highHp(caster);
        f.team.getActiveTeam().add(caster);
        named(caster, "Avatar_AuditCaster");
        named(f.entity, "Avatar_AuditHolder");
        caster.setFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD, .5f);
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD, 2f);
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_HEALED_ADD, .25f);
        var ability = testAbility(f);
        ability.getData().abilityName = "Avatar_AuditCaster_TestHeal";
        assertTrue(new ActionHealHP().execute(ability, heal(), ByteString.EMPTY, f.entity));
        assertEquals(2750, hp(f.entity));
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 1000);
        var action = heal();
        action.otherTargets = Map.of("base", "CurLocalAvatar");
        action.amountByTargetCurrentHPRatio = new DynamicFloat(.1f);
        assertTrue(new ActionHealHP().execute(ability, action, ByteString.EMPTY, caster));
        assertEquals(2925, hp(f.entity));
        assertEquals(1000, hp(caster));
    }

    @Test void spreadHealingUsesCasterOutgoingAndEachRecipientIncomingBonus() throws Exception {
        var f = healingFixture();
        set(f.entity.getAvatar(), Avatar.class, "avatarId", 10000089);
        var caster = second(f);
        var member = second(f);
        highHp(caster);
        highHp(member);
        f.team.getActiveTeam().addAll(List.of(caster, member));
        caster.setFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD, .5f);
        member.setFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD, 3f);
        member.setFightProperty(FightProperty.FIGHT_PROP_HEALED_ADD, .1f);
        f.entity.setFightProperty(FightProperty.FIGHT_PROP_HEALED_ADD, .25f);
        var ability = testAbility(f);
        set(ability, Ability.class, "owner", caster);
        ability.getData().abilityName = "Avatar_Furina_Constellation_2";
        assertTrue(new ActionHealHP().execute(ability, heal(), ByteString.EMPTY, f.entity));
        assertEquals(2750, hp(f.entity));
        assertEquals(2500, hp(caster));
        assertEquals(2600, hp(member));
    }

    @Test void unionDamageWaitsForEndAndUsesEnqueueSceneAcrossTransition() throws Exception {
        var f = fixture();
        var destination = fixture();
        var original = f.scene;
        var dispatcher = dispatcher(f);
        var commands = new ArrayList<UnionCmd>();
        commands.add(combatCommand(f, 10));
        dispatcher.put(20001, new PacketHandler() {
            @Override public void handle(GameSession session, byte[] header, byte[] payload) {
                assertEquals(100, hp(f.entity), "damage waits for batch completion");
                f.player.scene = destination.scene;
            }
        });
        commands.add(UnionCmd.newBuilder().setMessageId(20001).build());
        new HandlerUnionCmdNotify().handle(f.session, new byte[0], UnionCmdNotify.newBuilder().addAllCmdList(commands).build().toByteArray());
        assertEquals(90, hp(f.entity));
        assertEquals(1, original.attacks);
        assertEquals(0, destination.scene.attacks);
    }

    @Test void mixedValidAndMalformedCombatUnionDiscardsQueuedDamageEvenWhenDispatcherCatches() throws Exception {
        var f = fixture();
        dispatcher(f);
        var malformed = UnionCmd.newBuilder().setMessageId(PacketOpcodes.CombatInvocationsNotify)
                .setBody(ByteString.copyFrom(new byte[]{(byte) 0x80}));
        var union = UnionCmdNotify.newBuilder().addCmdList(combatCommand(f, 10)).addCmdList(malformed).build();
        new HandlerUnionCmdNotify().handle(f.session, new byte[0], union.toByteArray());
        assertEquals(100, hp(f.entity));
        assertEquals(0, f.scene.attacks);
        hit(new HandlerCombatInvocationsNotify(), f, 10);
        assertEquals(90, hp(f.entity), "failed batch leaves no stale queued work");
        assertEquals(1, f.scene.attacks);
    }

    @Test void receiveListenerFailureDiscardsUnionButCancellationDoesNot() throws Exception {
        for (boolean throwing : new boolean[]{true, false}) {
            var f = fixture();
            dispatcher(f);
            var field = Grasscutter.class.getDeclaredField("pluginManager");
            field.setAccessible(true);
            var old = field.get(null);
            var plugins = allocate(PluginManager.class);
            var handler = new EventHandler<>(emu.grasscutter.server.event.game.ReceivePacketEvent.class);
            var callbacks = new HashMap<Class<? extends Event>, List<EventHandler<? extends Event>>>();
            callbacks.put(emu.grasscutter.server.event.game.ReceivePacketEvent.class, List.of(handler));
            set(plugins, PluginManager.class, "handlers", callbacks);
            int[] children = {0};
            handler.listener(event -> {
                if (++children[0] == 2) {
                    assertEquals(100, hp(f.entity));
                    assertEquals(0, f.scene.attacks);
                    if (throwing) throw new IllegalStateException("second combat child");
                    event.cancel();
                }
            });
            field.set(null, plugins);
            try {
                var union = UnionCmdNotify.newBuilder().addCmdList(combatCommand(f, 10))
                        .addCmdList(combatCommand(f, 20)).build();
                new HandlerUnionCmdNotify().handle(f.session, new byte[0], union.toByteArray());
                assertEquals(throwing ? 100 : 90, hp(f.entity));
                assertEquals(throwing ? 0 : 1, f.scene.attacks);
                f.session.getServer().getPacketHandler().handle(f.session,
                        PacketOpcodes.CombatInvocationsNotify, new byte[0], combatCommand(f, 10).getBody().toByteArray());
                assertEquals(throwing ? 90 : 80, hp(f.entity));
                assertEquals(throwing ? 1 : 2, f.scene.attacks);
            } finally { field.set(null, old); }
        }
    }

    @Test void outermostScopeDrainsOnceAndFailedOuterScopeDiscardsNewWork() throws Exception {
        var f = fixture();
        var outer = CombatDamageBatch.open(f.session);
        try (outer) {
            hit(new HandlerCombatInvocationsNotify(), f, 10);
            assertEquals(100, hp(f.entity));
            outer.complete();
        }
        outer.close();
        assertEquals(90, hp(f.entity));
        assertEquals(1, f.scene.attacks);
        try (var failed = CombatDamageBatch.open(f.session)) {
            hit(new HandlerCombatInvocationsNotify(), f, 10);
        }
        assertEquals(90, hp(f.entity));
        assertEquals(1, f.scene.attacks);
    }

    @Test void eventSelectedDeadForeignOrNullAvatarIsRejectedAndLivingReplacementIndexMatches() throws Exception {
        var f = fixture();
        var requested = second(f);
        var replacement = second(f);
        f.team.getActiveTeam().addAll(List.of(requested, replacement));
        var field = Grasscutter.class.getDeclaredField("pluginManager");
        field.setAccessible(true);
        var old = field.get(null);
        var plugins = allocate(PluginManager.class);
        var handler = new EventHandler<>(PlayerSwitchAvatarEvent.class);
        var callbacks = new HashMap<Class<? extends Event>, List<EventHandler<? extends Event>>>();
        callbacks.put(PlayerSwitchAvatarEvent.class, List.of(handler));
        set(plugins, PluginManager.class, "handlers", callbacks);
        field.set(null, plugins);
        try {
            for (Avatar invalid : new Avatar[]{null, fixture().entity.getAvatar(), replacement.getAvatar()}) {
                replacement.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 0);
                handler.listener(event -> event.setNewAvatar(invalid));
                f.team.changeAvatar(requested.getAvatar().getGuid());
                assertEquals(0, f.team.getCurrentCharacterIndex());
                assertEquals(0, f.scene.replacements);
            }
            replacement.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 100);
            handler.listener(event -> event.setNewAvatar(replacement.getAvatar()));
            f.team.changeAvatar(requested.getAvatar().getGuid());
            assertEquals(2, f.team.getCurrentCharacterIndex());
            assertEquals(1, f.scene.replacements);
        } finally { field.set(null, old); }
    }

    @Test void nestedSwitchRejectsStaleOuterSelectionAndLeavesOneOnFieldAvatar() throws Exception {
        var f = fixture();
        var requested = second(f);
        var nested = second(f);
        f.team.getActiveTeam().addAll(List.of(requested, nested));
        f.scene.entities.remove(requested.getId());
        f.scene.entities.remove(nested.getId());
        var field = Grasscutter.class.getDeclaredField("pluginManager");
        field.setAccessible(true);
        var old = field.get(null);
        var plugins = allocate(PluginManager.class);
        var handler = new EventHandler<>(PlayerSwitchAvatarEvent.class);
        var callbacks = new HashMap<Class<? extends Event>, List<EventHandler<? extends Event>>>();
        callbacks.put(PlayerSwitchAvatarEvent.class, List.of(handler));
        set(plugins, PluginManager.class, "handlers", callbacks);
        boolean[] entered = {false};
        handler.listener(event -> {
            if (!entered[0]) {
                entered[0] = true;
                f.team.changeAvatar(nested.getAvatar().getGuid());
                assertSame(nested, f.team.getCurrentAvatarEntity());
            }
        });
        field.set(null, plugins);
        try {
            f.team.changeAvatar(requested.getAvatar().getGuid());
            assertTrue(entered[0]);
            assertSame(nested, f.team.getCurrentAvatarEntity());
            assertEquals(1, f.scene.replacements);
            assertEquals(Set.of(nested.getId()), f.scene.entities.keySet());
        } finally { field.set(null, old); }
    }

    private static Fixture healingFixture() throws Exception {
        var f = fixture();
        highHp(f.entity);
        return f;
    }
    private static void highHp(TestEntity entity) {
        entity.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, 10000);
        entity.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 1000);
    }
    private static float hp(GameEntity entity) { return entity.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP); }
    private static void named(TestEntity entity, String name) throws Exception {
        var data = new AvatarData();
        set(data, AvatarData.class, "name", name);
        set(entity.getAvatar(), Avatar.class, "avatarData", data);
    }
    private static AbilityModifier.AbilityModifierAction heal() {
        var action = new AbilityModifier.AbilityModifierAction();
        action.amount = new DynamicFloat(1000);
        return action;
    }
    private static UnionCmd combatCommand(Fixture f, float damage) {
        var attack = AttackResult.newBuilder().setAttackerId(f.entity.getId()).setDefenseId(f.entity.getId()).setDamage(damage);
        var entry = CombatInvokeEntry.newBuilder().setArgumentType(CombatTypeArgument.CombatTypeArgument_COMBAT_EVT_BEING_HIT)
                .setCombatData(EvtBeingHitInfo.newBuilder().setAttackResult(attack).build().toByteString());
        return UnionCmd.newBuilder().setMessageId(PacketOpcodes.CombatInvocationsNotify)
                .setBody(CombatInvocationsNotify.newBuilder().addInvokeList(entry).build().toByteString()).build();
    }
    private static Int2ObjectOpenHashMap<PacketHandler> dispatcher(Fixture f) throws Exception {
        var server = allocate(GameServer.class);
        var dispatcher = allocate(GameServerPacketHandler.class);
        var handlers = new Int2ObjectOpenHashMap<PacketHandler>();
        handlers.put(PacketOpcodes.CombatInvocationsNotify, new HandlerCombatInvocationsNotify());
        set(dispatcher, GameServerPacketHandler.class, "handlers", handlers);
        set(server, GameServer.class, "packetHandler", dispatcher);
        set(f.session, GameSession.class, "server", server);
        f.session.setState(GameSession.SessionState.ACTIVE);
        return handlers;
    }
}
