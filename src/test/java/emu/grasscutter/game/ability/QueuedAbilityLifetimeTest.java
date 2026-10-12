package emu.grasscutter.game.ability;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.binout.*;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.TeamManager;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

/** Queued ability work runs on a pool in no fixed order; liveness is scene identity. */
@ExtendWith(ServerResourceFixture.class)
final class QueuedAbilityLifetimeTest {
    @Test void queuedActionStillRunsAfterOwnerLeftTheEntityMap() throws Exception {
        var f = new Fixture();
        f.queueClear();
        f.scene.entities.remove(f.entity.getId());
        f.drain();
        assertNull(f.entity.values.get("lease_mark"), "a gadget's action may run after its own KillSelf");
    }

    @Test void queuedActionIsSkippedAfterOwnerLeftThePlayersScene() throws Exception {
        var f = new Fixture();
        f.queueClear();
        f.entity.scene = allocate(S.class);
        f.drain();
        assertEquals(7f, f.entity.values.get("lease_mark"));
    }

    @Test void queuedActionRunsWhenOwnerStaysInScene() throws Exception {
        var f = new Fixture();
        f.queueClear();
        f.drain();
        assertNull(f.entity.values.get("lease_mark"));
    }

    static final class Fixture {
        final P host = allocate(P.class);
        final W world = allocate(W.class);
        final S scene = allocate(S.class);
        final E entity = allocate(E.class);
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final AbilityManager manager = new AbilityManager(host, queue::add);
        final Ability ability = allocate(Ability.class);

        Fixture() throws Exception {
            world.host = host;
            host.manager = manager;
            host.team = new TeamManager();
            host.setWorld(world);
            host.setScene(scene);
            scene.world = world;
            scene.entities = new HashMap<>();
            entity.scene = scene;
            entity.values = new HashMap<>();
            entity.values.put("lease_mark", 7f);
            scene.entities.put(entity.getId(), entity);
            set(ability, Ability.class, "owner", entity);
            set(ability, Ability.class, "playerOwner", host);
            set(ability, Ability.class, "manager", manager);
            var data = new AbilityData();
            data.abilityName = "LifetimeFixture";
            set(ability, Ability.class, "data", data);
        }

        void queueClear() {
            var action = new AbilityModifierAction();
            action.type = AbilityModifierAction.Type.ClearGlobalValue;
            action.key = "lease_mark";
            manager.executeAction(ability, action, ByteString.EMPTY, entity);
            assertEquals(1, queue.size());
        }

        void drain() {
            while (!queue.isEmpty()) queue.remove().run();
        }
    }

    static final class P extends Player {
        AbilityManager manager;
        TeamManager team;
        @Override public AbilityManager getAbilityManager() { return manager; }
        @Override public TeamManager getTeamManager() { return team; }
        @Override public void sendPacket(BasePacket packet) {}
    }

    static final class W extends World {
        P host;
        W() { super(null); }
        @Override public Player getHost() { return host; }
    }

    static final class S extends Scene {
        W world;
        Map<Integer, GameEntity> entities;
        S() { super(null, null); }
        @Override public World getWorld() { return world; }
        @Override public Player getHost() { return world.host; }
        @Override public GameEntity getEntityById(int id) { return entities.get(id); }
    }

    static final class E extends EntityMonster {
        S scene;
        Map<String, Float> values;
        E() { super(null, null, null, null, 1); }
        @Override public Scene getScene() { return scene; }
        @Override public List<Ability> getInstancedAbilities() { return List.of(); }
        @Override public Map<String, Float> getGlobalAbilityValues() { return values; }
        @Override public void onAbilityValueUpdate() {}
    }

    private static <T> T allocate(Class<T> type) {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
