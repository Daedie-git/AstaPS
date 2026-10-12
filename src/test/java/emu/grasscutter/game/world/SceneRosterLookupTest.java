package emu.grasscutter.game.world;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.props.EntityIdType;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

final class SceneRosterLookupTest {
    @Test
    void offFieldAvatarLookupSurvivesRosterMutationDuringIdentityRead() throws Exception {
        lookupDuringMutation(false);
    }

    @Test
    void offFieldWeaponLookupSurvivesRosterMutationDuringIdentityRead() throws Exception {
        lookupDuringMutation(true);
    }

    private static void lookupDuringMutation(boolean weapon) throws Exception {
        var scene = allocate(Scene.class);
        var world = allocate(World.class);
        set(world, World.class, "entity", allocate(EntityWorld.class));
        set(scene, Scene.class, "world", world);
        set(scene, Scene.class, "entities", new ConcurrentHashMap<Integer, GameEntity>());
        set(scene, Scene.class, "weaponEntities", new ConcurrentHashMap<Integer, EntityWeapon>());
        var player = allocate(Player.class);
        var team = allocate(TeamManager.class);
        set(player, Player.class, "teamManager", team);
        set(team, TeamManager.class, "entity", allocate(EntityTeam.class));
        set(scene, Scene.class, "players", new CopyOnWriteArrayList<>(List.of(player)));
        var roster = Collections.synchronizedList(new ArrayList<EntityAvatar>());
        set(team, TeamManager.class, "avatars", roster);
        var first = allocate(MutatingAvatar.class);
        var target = allocate(MutatingAvatar.class);
        int id = ((weapon ? EntityIdType.WEAPON : EntityIdType.AVATAR).getId() << emu.grasscutter.GameConstants.ENTITY_ID_BIT_SHIFT) | 42;
        first.setId(1);
        target.setId(weapon ? 2 : id);
        target.weaponId = weapon ? id : 0;
        roster.addAll(List.of(first, target));
        try (var writer = Executors.newSingleThreadExecutor()) {
            first.mutation = () -> {
                assertFalse(Thread.holdsLock(roster), "Identity reads must occur outside the roster monitor");
                try {
                    writer.submit(() -> {
                        roster.clear();
                        roster.add(target);
                        roster.add(target); // Keep hasNext true so the stale iterator reaches next().
                    }).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            };
            assertSame(target, assertDoesNotThrow(() -> scene.getEntityById(id)));
            assertSame(target, scene.getEntityById(id), "Current off-field identity remains valid");
        }
    }

    private static final class MutatingAvatar extends EntityAvatar {
        Runnable mutation;
        int weaponId;
        MutatingAvatar() { super(null, null); }
        private void mutate() {
            var callback = mutation;
            mutation = null;
            if (callback != null) callback.run();
        }
        @Override public int getId() { mutate(); return super.getId(); }
        @Override public int getWeaponEntityId() { mutate(); return weaponId; }
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
}
