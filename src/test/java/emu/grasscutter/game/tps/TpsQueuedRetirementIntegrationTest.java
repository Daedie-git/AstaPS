package emu.grasscutter.game.tps;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.game.tps.TpsInventoryLifecycleTest.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.AbilityData;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.data.common.DynamicFloat;
import emu.grasscutter.game.ability.AbilityManager;
import emu.grasscutter.game.entity.EntityWeapon;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.scripts.SceneScriptManager;
import java.util.*;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

/** Actual weapon construction queues initialization before registration; disposal must retire it. */
@ExtendWith(ServerResourceFixture.class)
final class TpsQueuedRetirementIntegrationTest {
    private final TpsPreservationTest fixture = new TpsPreservationTest();
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private AbilityData previous;
    private static final String ABILITY = "TPS_Weapon_Audit_Fire";
    private static final String MARK = "retirement_mark";

    @BeforeEach void setup() throws Exception {
        fixture.setup();
        var player = fixture.fixture.player;
        set(player.world, emu.grasscutter.game.world.World.class, "entity",
                allocate(emu.grasscutter.game.entity.EntityWorld.class));
        player.team.setEntity(allocate(emu.grasscutter.game.entity.EntityTeam.class));
        var constructor = AbilityManager.class.getDeclaredConstructor(Player.class, Executor.class);
        constructor.setAccessible(true);
        set(player, Player.class, "abilityManager", constructor.newInstance(player, (Executor) queue::add));
        set(fixture.scene, Scene.class, "entities", new HashMap<Integer, GameEntity>());
        var action = new AbilityModifierAction();
        action.type = AbilityModifierAction.Type.SetGlobalValue;
        action.key = MARK;
        action.ratio = new DynamicFloat(7f);
        var data = new AbilityData();
        data.abilityName = ABILITY;
        data.modifiers = new HashMap<>();
        data.onAdded = new AbilityModifierAction[] {action};
        previous = GameData.getAbilityDataMap().put(ABILITY, data);
    }

    @AfterEach void restore() {
        if (previous == null) GameData.getAbilityDataMap().remove(ABILITY);
        else GameData.getAbilityDataMap().put(ABILITY, previous);
        fixture.restore();
    }

    private EntityWeapon wear() {
        var player = fixture.fixture.player;
        var gun = item(224001);
        assertTrue(player.inventory.addItem(gun));
        assertEquals(0, TpsWeaponSystem.wear(player, 1, List.of(gun.getGuid())));
        var entity = gun.getWeaponEntity();
        assertNotNull(entity);
        assertEquals(1, queue.size(), "Constructor must queue actual onAdded initialization");
        assertFalse(entity.getGlobalAbilityValues().containsKey(MARK));
        return entity;
    }

    private void drain() {
        while (!queue.isEmpty()) queue.remove().run();
    }

    @Test void currentInitializationStillRunsAfterRegistration() {
        var entity = wear();
        drain();
        assertEquals(7f, entity.getGlobalAbilityValues().get(MARK));
    }

    @Test void sameSceneUnequipRejectsConstructorQueuedWork() {
        var entity = wear();
        var epoch = entity.getAbilityWorkEpoch();
        assertEquals(0, TpsWeaponSystem.wear(fixture.fixture.player, 1, List.of()));
        assertTrue(fixture.scene.getWeaponEntities().isEmpty());
        drain();
        assertFalse(entity.getGlobalAbilityValues().containsKey(MARK), "Disposed gun ran queued initialization");
        assertNotSame(epoch, entity.getAbilityWorkEpoch());
    }

    @Test void groupUnloadRejectsWorkQueuedBeforeRegistration() throws Exception {
        var entity = wear();
        var epoch = entity.getAbilityWorkEpoch();
        entity.setGroupId(123);
        fixture.scene.getEntities().put(entity.getId(), entity);
        set(fixture.scene, Scene.class, "loadedGroups", new HashSet<>());
        var scripts = allocate(SceneScriptManager.class);
        set(scripts, SceneScriptManager.class, "sceneGroupsInstances", new HashMap<>());
        set(scripts, SceneScriptManager.class, "loadedGroupSetPerBlock", new HashMap<>());
        set(fixture.scene, Scene.class, "scriptManager", scripts);
        fixture.scene.unloadGroup(null, 123);
        assertFalse(fixture.scene.getEntities().containsKey(entity.getId()));
        drain();
        assertFalse(entity.getGlobalAbilityValues().containsKey(MARK), "Unloaded entity ran queued initialization");
        assertNotSame(epoch, entity.getAbilityWorkEpoch());
    }
}
