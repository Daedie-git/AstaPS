package emu.grasscutter.game.ability.actions;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.entity.*;
import org.junit.jupiter.api.Test;

class HealCasterResolutionTest {
    @Test void validNonAvatarOwnerIsNotReplacedByNamedCasterHeuristic() throws Exception {
        var ability = allocate(OverriddenAbility.class);
        ability.owner = allocate(EntityMonster.class);
        ability.caster = allocate(EntityAvatar.class);
        assertSame(ability.owner, resolve(ability));
    }
    @Test void scenelessGadgetCannotThrowDuringHealCasterResolution() throws Exception {
        var ability = allocate(OverriddenAbility.class);
        ability.owner = allocate(EntityClientGadget.class);
        assertNull(assertDoesNotThrow(() -> resolve(ability)));
    }
    @Test void legacyFurinaNestedGadgetFallbackStillFindsCurrentAvatar() throws Exception {
        var ability = allocate(OverriddenAbility.class);
        var root = allocate(EntityClientGadget.class);
        var nested = allocate(EntityClientGadget.class);
        nested.gadgetId = 41089013;
        var scene = allocate(GadgetScene.class); scene.nested = nested;
        set(root, GameEntity.class, "id", 1); set(nested, GameEntity.class, "id", 2);
        set(root, GameEntity.class, "scene", scene); set(nested, GameEntity.class, "scene", scene);
        set(root, EntityClientGadget.class, "ownerEntityId", 10);
        set(nested, EntityClientGadget.class, "ownerEntityId", 20);
        var player = allocate(emu.grasscutter.game.player.Player.class);
        var team = allocate(emu.grasscutter.game.player.TeamManager.class);
        var avatar = allocate(EntityAvatar.class);
        set(team, emu.grasscutter.game.player.TeamManager.class, "avatars", new java.util.ArrayList<>(java.util.List.of(avatar)));
        set(player, emu.grasscutter.game.player.Player.class, "teamManager", team);
        set(ability, Ability.class, "playerOwner", player); ability.owner = root;
        assertSame(avatar, resolve(ability));
    }
    static class GadgetScene extends emu.grasscutter.game.world.Scene {
        EntityClientGadget nested;
        GadgetScene() { super(null, null); }
        @Override public GameEntity getEntityById(int id) { return id == 10 ? nested : null; }
    }
    static void set(Object object, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    static GameEntity resolve(Ability ability) throws Exception {
        var method = ActionHealHP.class.getDeclaredMethod("resolveHealCaster", Ability.class);
        method.setAccessible(true);
        try { return (GameEntity) method.invoke(null, ability); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }
    static class OverriddenAbility extends Ability {
        GameEntity owner; EntityAvatar caster;
        OverriddenAbility() { super(null, null, null); }
        @Override public GameEntity getOwner() { return owner; }
        @Override public EntityAvatar getCasterEntity() { return caster; }
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) f.get(null)).allocateInstance(type));
    }
}
