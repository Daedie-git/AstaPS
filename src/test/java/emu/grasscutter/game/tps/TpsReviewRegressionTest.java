package emu.grasscutter.game.tps;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.game.tps.TpsInventoryLifecycleTest.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.world.Scene;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
final class TpsReviewRegressionTest {
    TpsInventoryLifecycleTest fixture = new TpsInventoryLifecycleTest();
    @BeforeEach void setup() throws Exception { fixture.setup(); }
    @AfterEach void restore() { fixture.restore(); }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "barrier timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    /** Wait for either the old unlocked caller to finish or the corrected caller to block. */
    static void settled(Thread worker, FutureTask<Boolean> result) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!result.isDone() && worker.getState() != Thread.State.BLOCKED) {
            assertTrue(System.nanoTime() < deadline, "worker neither completed nor serialized");
            Thread.onSpinWait();
        }
    }

    @Test void grantVersusActualDestructionNeverFailsAfterMaterialInsertion() throws Exception {
        var gun = item(224001);
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        gun.setTpsAccessoryIds(new ArrayList<>() {
            @Override public boolean add(Integer id) {
                boolean result = super.add(id);
                inserted.countDown(); await(release); return result;
            }
        });
        assertTrue(fixture.player.inventory.addItem(gun));
        var grant = new FutureTask<>(() -> fixture.player.inventory.addItem(item(223111)));
        var destroy = new FutureTask<>(() -> fixture.player.inventory.removeItem(gun, 1));
        var first = new Thread(grant); var second = new Thread(destroy);
        try {
            first.start(); await(inserted); second.start(); settled(second, destroy);
            release.countDown();
            assertTrue(grant.get(5, TimeUnit.SECONDS));
            assertTrue(destroy.get(5, TimeUnit.SECONDS));
            assertNull(TpsWeaponSystem.findOwnedWeapon(fixture.player, 224001));
            assertEquals(1, fixture.player.inventory.getItemCountById(223111));
        } finally {
            release.countDown(); first.join(5000); second.join(5000);
            assertFalse(first.isAlive()); assertFalse(second.isAlive());
        }
    }

    @Test void twoActualGrantsAppendExactlyOnePermanentUnlock() throws Exception {
        emu.grasscutter.audit.AuditFixtures.set(
                emu.grasscutter.data.GameData.getItemDataMap().get(223111), "stackLimit", 2);
        var gun = item(224001);
        var inspected = new CountDownLatch(1); var release = new CountDownLatch(1);
        gun.setTpsAccessoryIds(new ArrayList<>() {
            @Override public boolean contains(Object id) {
                boolean result = super.contains(id);
                if (Thread.currentThread().getName().equals("first-grant")) {
                    inspected.countDown(); await(release);
                }
                return result;
            }
        });
        assertTrue(fixture.player.inventory.addItem(gun));
        var a = new FutureTask<>(() -> fixture.player.inventory.addItem(item(223111)));
        var b = new FutureTask<>(() -> fixture.player.inventory.addItem(item(223111)));
        var first = new Thread(a, "first-grant"); var second = new Thread(b, "second-grant");
        try {
            first.start(); await(inspected); second.start(); settled(second, b);
            release.countDown(); assertTrue(a.get(5, TimeUnit.SECONDS)); assertTrue(b.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(224505), gun.getTpsAccessoryIds());
            assertEquals(2, gun.saves); // Initial insertion and exactly one unlock save.
        } finally {
            release.countDown(); first.join(5000); second.join(5000);
            assertFalse(first.isAlive()); assertFalse(second.isAlive());
        }
    }

    @Test void commandUnlockVersusActualGrantAppendsExactlyOnce() throws Exception {
        var gun = item(224001);
        var inspected = new CountDownLatch(1); var release = new CountDownLatch(1);
        gun.setTpsAccessoryIds(new ArrayList<>(List.of(999999)) {
            @Override public boolean contains(Object id) {
                boolean result = super.contains(id);
                if (Thread.currentThread().getName().equals("material-grant")) {
                    inspected.countDown(); await(release);
                }
                return result;
            }
        });
        assertTrue(fixture.player.inventory.addItem(gun));
        var grant = new FutureTask<>(() -> fixture.player.inventory.addItem(item(223111)));
        var command = new FutureTask<>(() -> {
            assertEquals(0, new emu.grasscutter.command.commands.TpsCommand()
                    .createCommandLine(null, fixture.player).execute("accessory"));
            return true;
        });
        var first = new Thread(grant, "material-grant"); var second = new Thread(command, "unlock-command");
        try {
            first.start(); await(inspected); second.start(); settled(second, command);
            release.countDown(); assertTrue(grant.get(5, TimeUnit.SECONDS)); assertTrue(command.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(999999, 224505), gun.getTpsAccessoryIds());
            assertEquals(2, gun.saves);
        } finally {
            release.countDown(); first.join(5000); second.join(5000);
            assertFalse(first.isAlive()); assertFalse(second.isAlive());
        }
    }

    @Test void commandUnlockNeedsNoMaterial() {
        var gun = item(224001); assertTrue(fixture.player.inventory.addItem(gun));
        assertEquals(0, new emu.grasscutter.command.commands.TpsCommand()
                .createCommandLine(null, fixture.player).execute("accessory"));
        assertEquals(List.of(224505), gun.getTpsAccessoryIds());
        assertEquals(0, fixture.player.inventory.getItemCountById(223111));
    }

    @Test void automaticTrialDressingThenIdenticalWearHasOneDurableOwner() throws Exception {
        var p = fixture.player;
        var gun = item(224001); assertTrue(p.inventory.addItem(gun));
        var owned = fixture.wearer(1, 0); p.avatars.getAvatars().put(1, owned);
        var trial = fixture.wearer(TpsAvatarSystem.TPS_AVATAR_FEMALE, 10065); trial.ids.clear();
        TpsAvatarSystem.onTrialAvatarCreated(trial);
        assertTrue(owned.ids.isEmpty(), "automatic trial dressing must detach the owned wearer");
        p.team.getTrialAvatars().put(trial.id, trial);
        assertEquals(0, TpsWeaponSystem.wear(p, trial.getGuid(), List.of(gun.getGuid())));
        assertEquals(List.of(224001), trial.ids);
        assertEquals(List.of(224001), p.loadout);
        assertEquals(1, owned.saves); assertEquals(0, trial.saves);
        assertEquals(1, p.saves);
    }

    static EntityAvatar avatarEntity(Avatar avatar, Scene scene, int id) throws Exception {
        var entity = allocate(EntityAvatar.class);
        set(entity, EntityAvatar.class, "avatar", avatar);
        set(entity, GameEntity.class, "scene", scene); entity.setId(id);
        return entity;
    }

    @Test void sceneDepartureRetiresOffFieldGunButPreservesPermanentChoices() throws Exception {
        var p = fixture.player; var gun = item(224001); assertTrue(p.inventory.addItem(gun));
        gun.getTpsAccessoryIds().add(224505); p.loadout.add(224001);
        var owned = fixture.wearer(1, 0); p.avatars.getAvatars().put(1, owned);
        var scene = attachEntity(gun);
        var entity = avatarEntity(owned, scene, 28); p.team.getActiveTeam().add(entity);
        set(scene, Scene.class, "entities", new HashMap<Integer, GameEntity>());
        // A second player's entity keeps the old scene alive; the departing avatar is off-field.
        var otherPlayer = allocate(P.class);
        otherPlayer.team = new TeamManager();
        var otherAvatar = allocate(A.class);
        otherAvatar.player = otherPlayer; otherAvatar.id = 3; otherAvatar.ids = new ArrayList<>();
        var other = avatarEntity(otherAvatar, scene, 29);
        otherPlayer.team.getActiveTeam().add(other);
        set(scene, Scene.class, "players", new ArrayList<>(List.of(p, otherPlayer)));
        scene.getEntities().put(29, other);
        var depart = Scene.class.getDeclaredMethod("removePlayerAvatars", Player.class);
        depart.setAccessible(true); depart.invoke(scene, p);
        assertTrue(gun.getWeaponEntity() == null, "departing gun entity retained"); assertTrue(scene.getWeaponEntities().isEmpty());
        assertSame(other, scene.getEntities().get(29)); assertTrue(p.team.getActiveTeam().isEmpty());
        assertTrue(scene.getPlayers().stream().anyMatch(member -> member == otherPlayer));
        assertEquals(List.of(other), otherPlayer.team.getActiveTeam());
        assertEquals(List.of(224001), owned.ids); assertEquals(List.of(224001), p.loadout);
        assertEquals(List.of(224505), gun.getTpsAccessoryIds()); assertEquals(0, owned.saves);
    }

    static final class ExitScene extends Scene {
        ExitScene() { super(null, null); }
        @Override public void addEntity(GameEntity entity) {} // Restored team's spawn transport.
        @Override public void broadcastPacket(emu.grasscutter.net.packet.BasePacket packet) {}
    }

    static final class PartyScene extends Scene {
        PartyScene() { super(null, null); }
        @Override public void broadcastPacket(emu.grasscutter.net.packet.BasePacket packet) {}
    }

    @Test void ordinaryPartyReplacementRetiresDiscardedWearerBeforeDeparture() throws Exception {
        verifyPartyReplacement(false);
    }

    @Test void ordinaryPartyReplacementRetiresDiscardedCurrentWearer() throws Exception {
        verifyPartyReplacement(true);
    }

    void verifyPartyReplacement(boolean discardCurrent) throws Exception {
        var p = fixture.player; var team = new TeamManager(p); p.team = team;
        set(team, TeamManager.class, "teams", new LinkedHashMap<Integer, TeamInfo>());
        team.getTeams().put(1, new TeamInfo(new ArrayList<>(List.of(2))));
        var gun = item(224001); assertTrue(p.inventory.addItem(gun));
        gun.getTpsAccessoryIds().add(224505); p.loadout.add(224001);
        var discarded = fixture.wearer(1, 0); var retained = fixture.wearer(2, 0); retained.ids.clear();
        p.avatars.getAvatars().put(1, discarded); p.avatars.getAvatars().put(2, retained);
        var oldScene = attachEntity(gun); var scene = allocate(PartyScene.class); p.scene = scene;
        set(scene, Scene.class, "weaponEntities", oldScene.getWeaponEntities());
        set(scene, Scene.class, "entities", new HashMap<Integer, GameEntity>());
        set(gun.getWeaponEntity(), GameEntity.class, "scene", scene);
        var a = avatarEntity(discarded, scene, 28); var b = avatarEntity(retained, scene, 29);
        team.getActiveTeam().addAll(discardCurrent ? List.of(a, b) : List.of(b, a));
        scene.getEntities().put(28, a); scene.getEntities().put(29, b);
        var otherPlayer = allocate(P.class); otherPlayer.team = new TeamManager();
        set(scene, Scene.class, "players", new ArrayList<>(List.of(p, otherPlayer)));
        var update = TeamManager.class.getDeclaredMethod("updateTeamEntities",
                emu.grasscutter.net.packet.BasePacket.class, java.util.function.Consumer.class);
        update.setAccessible(true);
        update.invoke(team, null, (java.util.function.Consumer<EntityAvatar>) previous -> {});
        assertEquals(List.of(b), team.getActiveTeam());
        assertTrue(gun.getWeaponEntity() == null, "ordinary party discard retained gun");
        assertTrue(scene.getWeaponEntities().isEmpty()); assertSame(b, scene.getEntities().get(29));
        var depart = Scene.class.getDeclaredMethod("removePlayerAvatars", Player.class);
        depart.setAccessible(true); depart.invoke(scene, p);
        assertTrue(scene.getPlayers().stream().anyMatch(member -> member == otherPlayer));
        assertEquals(List.of(224001), discarded.ids); assertEquals(List.of(224001), p.loadout);
        assertEquals(List.of(224505), gun.getTpsAccessoryIds()); assertEquals(1, discarded.saves);
    }

    @Test void actualGunRemovalClearsExistingRuntimeState() throws Exception {
        var gun = item(224001); assertTrue(fixture.player.inventory.addItem(gun));
        var scene = attachEntity(gun); var old = gun.getWeaponEntity();
        var field = EnvironmentalSealHelper.class.getDeclaredField("burnLeftByEntityId");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var state = (Map<Integer, Float>) field.get(null);
        state.put(old.getId(), 12f); state.put(123456, 34f);
        try {
            assertTrue(fixture.player.inventory.removeItem(gun, 1));
            assertFalse(state.containsKey(old.getId()), "disposed TPS weapon bypassed runtime cleanup");
            assertEquals(34f, state.get(123456));
            assertNull(gun.getWeaponEntity()); assertTrue(scene.getWeaponEntities().isEmpty());
        } finally { state.remove(old.getId()); state.remove(123456); }
    }

    @Test void actualTrialTeamTeardownRetiresOffFieldGunWithoutSavingTrial() throws Exception {
        var p = fixture.player; var team = new TeamManager(p); p.team = team;
        set(team, TeamManager.class, "teams", new LinkedHashMap<Integer, TeamInfo>());
        team.getTeams().put(1, new TeamInfo());
        var gun = item(224001); assertTrue(p.inventory.addItem(gun));
        gun.getTpsAccessoryIds().add(224505); p.loadout.add(224001);
        var trial = fixture.wearer(TpsAvatarSystem.TPS_AVATAR_FEMALE, 10065);
        team.getTrialAvatars().put(trial.id, trial);
        var oldScene = attachEntity(gun);
        var scene = allocate(ExitScene.class); p.scene = scene;
        set(scene, Scene.class, "weaponEntities", oldScene.getWeaponEntities());
        set(gun.getWeaponEntity(), GameEntity.class, "scene", scene);
        set(scene, Scene.class, "entities", new HashMap<Integer, GameEntity>());
        team.getActiveTeam().add(avatarEntity(trial, scene, 28));
        p.world = allocate(emu.grasscutter.game.world.World.class);
        set(p.world, emu.grasscutter.game.world.World.class, "players", new ArrayList<Player>());
        team.setEntity(allocate(EntityTeam.class));
        team.removeTrialAvatarTeam();
        assertTrue(gun.getWeaponEntity() == null, "trial gun entity retained"); assertTrue(scene.getWeaponEntities().isEmpty());
        assertTrue(team.getTrialAvatars().isEmpty()); assertEquals(0, trial.saves);
        assertEquals(List.of(224001), p.loadout); assertEquals(List.of(224505), gun.getTpsAccessoryIds());
    }
}
