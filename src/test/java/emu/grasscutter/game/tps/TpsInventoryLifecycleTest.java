package emu.grasscutter.game.tps;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.tps.*;
import emu.grasscutter.game.avatar.*;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.*;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.utils.JsonUtils;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
final class TpsInventoryLifecycleTest {
    P player;
    Map<Integer, ItemData> oldItems;
    TpsWeaponData oldWeapon;
    TpsWeaponAccessoryData oldAccessory;

    @BeforeEach void setup() throws Exception {
        player = allocate(P.class);
        player.inventory = new Inventory(player);
        player.avatars = new AvatarStorage(player);
        player.team = new TeamManager();
        player.codex = new PlayerCodex(player);
        player.loadout = new ArrayList<>();
        player.packets = new ArrayList<>();
        oldItems = new HashMap<>();
        for (int id : List.of(224001, 223111)) oldItems.put(id, GameData.getItemDataMap().get(id));
        GameData.getItemDataMap().put(224001, JsonUtils.decode("{\"id\":224001,\"itemType\":\"ITEM_TPS_WEAPON\",\"gadgetId\":50016001,\"stackLimit\":1}", ItemData.class));
        GameData.getItemDataMap().put(223111, JsonUtils.decode("{\"id\":223111,\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_TPS_ACCESSORY\",\"stackLimit\":1}", ItemData.class));
        oldWeapon = GameData.getTpsWeaponDataMap().put(224001, JsonUtils.decode("{\"id\":224001,\"NNJGEAPMJFL\":1}", TpsWeaponData.class));
        oldAccessory = GameData.getTpsWeaponAccessoryDataMap().put(224505, JsonUtils.decode("{\"id\":224505,\"tpsWeaponId\":224001,\"KOHCBGDKMPG\":223111}", TpsWeaponAccessoryData.class));
    }

    @AfterEach void restore() {
        oldItems.forEach((id, value) -> { if (value == null) GameData.getItemDataMap().remove(id); else GameData.getItemDataMap().put(id, value); });
        if (oldWeapon == null) GameData.getTpsWeaponDataMap().remove(224001); else GameData.getTpsWeaponDataMap().put(224001, oldWeapon);
        if (oldAccessory == null) GameData.getTpsWeaponAccessoryDataMap().remove(224505); else GameData.getTpsWeaponAccessoryDataMap().put(224505, oldAccessory);
    }

    @Test void weaponThenMaterialPermanentlyUnionsUnlocksWithoutDuplicates() {
        var gun = item(224001);
        gun.getTpsAccessoryIds().add(999999); // Command-granted IDs remain untouched.
        assertTrue(player.inventory.addItem(gun));
        var material = item(223111);
        assertTrue(player.inventory.addItem(material));
        assertFalse(player.inventory.addItem(item(223111)), "Full accessory stack must reject a duplicate grant");
        assertEquals(List.of(999999, 224505), gun.getTpsAccessoryIds());
        assertTrue(player.inventory.removeItem(material, 1));
        assertEquals(List.of(999999, 224505), gun.getTpsAccessoryIds());
    }

    @Test void batchGrantAndSerializedExchangeUsePermanentUnlockCallback() {
        var gun = item(224001);
        assertTrue(player.inventory.tryAddItems(List.of(gun, item(223111)), null));
        assertEquals(List.of(224505), gun.getTpsAccessoryIds());
        assertTrue(player.inventory.tryPayAndAddItems(
                List.of(new emu.grasscutter.data.common.ItemParamData(223111, 1)), 1,
                List.of(item(223111)), null));
        assertEquals(List.of(224505), gun.getTpsAccessoryIds());
        assertEquals(2, gun.saves);
    }

    @Test void materialThenWeaponAlsoUnlocks() {
        assertTrue(player.inventory.addItem(item(223111)));
        var gun = item(224001);
        assertTrue(player.inventory.addItem(gun));
        assertEquals(List.of(224505), gun.getTpsAccessoryIds());
    }

    @Test void exhaustingWeaponClearsOwnedTrialAndLoadoutReferencesAndEntity() throws Exception {
        var gun = item(224001);
        assertTrue(player.inventory.addItem(gun));
        var owned = wearer(1, 0);
        var trial = wearer(2, 44);
        player.avatars.getAvatars().put(1, owned);
        player.team.getTrialAvatars().put(2, trial);
        player.loadout.add(224001);
        var scene = attachEntity(gun);
        assertTrue(player.inventory.removeItem(gun, 1));
        assertTrue(owned.ids.isEmpty());
        assertTrue(trial.ids.isEmpty());
        assertTrue(player.loadout.isEmpty());
        assertTrue(scene.getWeaponEntities().isEmpty());
        assertNull(gun.getWeaponEntity());
        assertEquals(1, owned.saves);
        assertEquals(0, trial.saves);
        assertEquals(1, owned.recalculations);
        assertEquals(1, trial.recalculations);
    }

    @Test void replacingWearListWithEmptyDropsOldWeaponEntity() throws Exception {
        var gun = item(224001);
        assertTrue(player.inventory.addItem(gun));
        var owned = wearer(1, 0);
        player.avatars.getAvatars().put(1, owned);
        var guidField = AvatarStorage.class.getDeclaredField("avatarsGuid");
        guidField.setAccessible(true);
        @SuppressWarnings("unchecked") var guids =
                (it.unimi.dsi.fastutil.longs.Long2ObjectMap<Avatar>) guidField.get(player.avatars);
        guids.put(1, owned);
        var scene = attachEntity(gun);
        assertEquals(0, TpsWeaponSystem.wear(player, 1, List.of()));
        assertTrue(scene.getWeaponEntities().isEmpty());
        assertNull(gun.getWeaponEntity());
    }

    @Test void completedBagLoadPrunesOnlyMissingWeaponsAndNeverSavesTrials() throws Exception {
        assertTrue(player.inventory.addItem(item(224001)));
        var owned = wearer(1, 0);
        owned.ids.add(999999);
        var trial = wearer(2, 44);
        trial.ids.add(999999);
        player.avatars.getAvatars().put(1, owned);
        player.team.getTrialAvatars().put(2, trial);
        player.loadout.addAll(List.of(224001, 999999));
        TpsWeaponSystem.onInventoryLoaded(player);
        assertEquals(List.of(224001), owned.ids);
        assertEquals(List.of(224001), trial.ids);
        assertEquals(List.of(224001), player.loadout);
        assertEquals(1, owned.saves);
        assertEquals(0, trial.saves);
    }

    @Test void rejectedAccessoryGrantDoesNotUnlockAnything() {
        var gun = item(224001);
        assertTrue(player.inventory.addItem(gun));
        player.inventory.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(0));
        assertFalse(player.inventory.addItem(item(223111)));
        assertTrue(gun.getTpsAccessoryIds().isEmpty());
    }

    @Test void movingGunFromTrialAvatarDoesNotPersistTrialOrKeepItsEntity() throws Exception {
        var gun = item(224001);
        assertTrue(player.inventory.addItem(gun));
        var owned = wearer(1, 0);
        owned.ids.clear();
        var trial = wearer(2, 44);
        player.avatars.getAvatars().put(1, owned);
        player.team.getTrialAvatars().put(2, trial);
        var guidField = AvatarStorage.class.getDeclaredField("avatarsGuid");
        guidField.setAccessible(true);
        @SuppressWarnings("unchecked") var guids =
                (it.unimi.dsi.fastutil.longs.Long2ObjectMap<Avatar>) guidField.get(player.avatars);
        guids.put(1, owned);
        var scene = attachEntity(gun);
        assertEquals(0, TpsWeaponSystem.wear(player, 1, List.of(gun.getGuid())));
        assertTrue(trial.ids.isEmpty());
        assertEquals(List.of(224001), owned.ids);
        assertEquals(0, trial.saves);
        assertTrue(scene.getWeaponEntities().isEmpty());
        assertNull(gun.getWeaponEntity());
    }

    static MemoryItem item(int id) { return new MemoryItem(GameData.getItemDataMap().get(id)); }
    A wearer(int id, int trialId) throws Exception {
        var avatar = allocate(A.class);
        avatar.player = player;
        avatar.ids = new ArrayList<>(List.of(224001));
        avatar.id = id;
        avatar.trial = trialId;
        return avatar;
    }
    static Scene attachEntity(GameItem item) throws Exception {
        var scene = allocate(Scene.class);
        set(scene, Scene.class, "weaponEntities", new HashMap<Integer, GameEntity>());
        var entity = allocate(EntityWeapon.class);
        set(entity, GameEntity.class, "scene", scene);
        entity.setId(27);
        scene.getWeaponEntities().put(27, entity);
        item.setWeaponEntity(entity);
        return scene;
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return type.cast(((Unsafe) f.get(null)).allocateInstance(type));
    }
    static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    static final class MemoryItem extends GameItem {
        MemoryItem(ItemData data) { super(data, 1); }
        int saves;
        @Override public void save() { saves++; }
    }
    static final class P extends Player {
        Inventory inventory; AvatarStorage avatars; TeamManager team; PlayerCodex codex;
        List<Integer> loadout; long next; int saves; boolean loggedIn;
        Scene scene; emu.grasscutter.game.world.World world;
        List<BasePacket> packets = new ArrayList<>();
        @Override public int getUid() { return 1; }
        @Override public long getNextGameGuid() { return ++next; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public AvatarStorage getAvatars() { return avatars; }
        @Override public TeamManager getTeamManager() { return team; }
        @Override public PlayerCodex getCodex() { return codex; }
        @Override public List<Integer> getTpsLoadout() { return loadout; }
        @Override public Scene getScene() { return scene; }
        @Override public emu.grasscutter.game.world.World getWorld() { return world; }
        @Override public boolean isInMultiplayer() { return false; }
        @Override public boolean hasSentLoginPackets() { return loggedIn; }
        @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
        @Override public void save() { saves++; }
    }
    static final class A extends Avatar {
        P player; List<Integer> ids; int id; int trial; int saves; int recalculations;
        @Override public Player getPlayer() { return player; }
        @Override public List<Integer> getTpsWeaponIds() { return ids; }
        @Override public int getAvatarId() { return id; }
        @Override public long getGuid() { return id; }
        @Override public int getTrialAvatarId() { return trial; }
        @Override public void save() { saves++; }
        @Override public void recalcStats() { recalculations++; }
    }
}
