package emu.grasscutter.game.tps;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.game.tps.TpsInventoryLifecycleTest.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.ResourceLoader.OpenConfigData;
import emu.grasscutter.data.binout.OpenConfigEntry;
import emu.grasscutter.data.binout.config.ConfigEntityGadget;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.data.excels.tps.*;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.AbilityChangeNotifyOuterClass.AbilityChangeNotify;
import emu.grasscutter.net.proto.TpsEquipChangeNotifyOuterClass.TpsEquipChangeNotify;
import emu.grasscutter.net.proto.StoreItemChangeNotifyOuterClass.StoreItemChangeNotify;
import emu.grasscutter.utils.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

/** Records only storage/transport boundaries; stat calculation and packet/entity builders are real. */
@ExtendWith(ServerResourceFixture.class)
final class TpsPreservationTest {
    TpsInventoryLifecycleTest fixture = new TpsInventoryLifecycleTest();
    Map<Map<?, ?>, Map<?, ?>> backups = new IdentityHashMap<>();
    MemoryAvatar avatar;
    Scene scene;

    void backup(Map<?, ?> map) { backups.put(map, new HashMap<>(map)); }
    @BeforeEach void setup() throws Exception {
        fixture.setup();
        backup(GameData.getItemDataMap()); backup(GameData.getTpsWeaponDataMap());
        backup(GameData.getTpsWeaponAccessoryDataMap()); backup(GameData.getEquipAffixDataMap());
        backup(GameData.getOpenConfigEntries()); backup(GameData.getGadgetDataMap());
        backup(GameData.getGadgetConfigData());
        for (int id : List.of(224001, 224002)) {
            GameData.getItemDataMap().put(id, JsonUtils.decode("{\"id\":" + id + ",\"itemType\":\"ITEM_TPS_WEAPON\",\"gadgetId\":50016001,\"stackLimit\":1}", ItemData.class));
            GameData.getTpsWeaponDataMap().put(id, JsonUtils.decode("{\"id\":" + id + ",\"wearSlotType\":1,\"equipAffixId\":9900010}", TpsWeaponData.class));
        }
        GameData.getTpsWeaponAccessoryDataMap().put(224505, JsonUtils.decode("{\"id\":224505,\"tpsWeaponId\":224001,\"unlockMaterialId\":223111,\"equipAffixId\":9900020}", TpsWeaponAccessoryData.class));
        affix(9900010, 10, "AuditTpsBase", "Avatar_TPS_Audit_Base");
        affix(9900020, 7, "AuditTpsAccessory", "Avatar_TPS_Audit_Accessory");
        GameData.getGadgetDataMap().put(50016001, JsonUtils.decode("{\"id\":50016001,\"jsonName\":\"AuditTpsGun\"}", GadgetData.class));
        GameData.getGadgetConfigData().put("AuditTpsGun", JsonUtils.decode("{\"abilities\":[{\"abilityName\":\"TPS_Weapon_Audit_Fire\"},{\"abilityName\":\"TPS_Weapon_Audit_Reload\"}]}", ConfigEntityGadget.class));
        var p = fixture.player;
        p.world = allocate(World.class);
        set(p.world, World.class, "nextEntityId", new AtomicInteger());
        set(p.world, World.class, "host", p);
        var recordingScene = allocate(RecordingScene.class);
        recordingScene.player = p;
        scene = recordingScene;
        set(scene, Scene.class, "world", p.world);
        set(scene, Scene.class, "sceneData", JsonUtils.decode("{\"id\":3}", emu.grasscutter.data.excels.scene.SceneData.class));
        set(scene, Scene.class, "players", new ArrayList<>(List.of(p)));
        set(scene, Scene.class, "weaponEntities", new HashMap<Integer, GameEntity>());
        p.scene = scene;
        avatar = new MemoryAvatar(p);
        var data = new AvatarData();
        set(data, AvatarData.class, "fetters", new ArrayList<Integer>());
        set(data, AvatarData.class, "name", "AuditTps");
        set(avatar, Avatar.class, "avatarData", data);
        set(avatar, Avatar.class, "proudSkillList", new it.unimi.dsi.fastutil.ints.IntOpenHashSet());
        set(avatar, Avatar.class, "talentIdList", new it.unimi.dsi.fastutil.ints.IntOpenHashSet());
        p.avatars.getAvatars().put(1, avatar);
        var field = emu.grasscutter.game.avatar.AvatarStorage.class.getDeclaredField("avatarsGuid");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var guids = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<Avatar>) field.get(p.avatars);
        guids.put(1, avatar);
        p.team.getActiveTeam().add(TpsReviewRegressionTest.avatarEntity(avatar, scene, 28));
        p.loggedIn = true;
    }

    void affix(int id, int value, String config, String ability) {
        var affix = JsonUtils.decode("{\"affixId\":" + id + ",\"openConfig\":\"" + config + "\",\"addProps\":[{\"propType\":\"FIGHT_PROP_ATTACK\",\"value\":" + value + "}]}", EquipAffixData.class);
        affix.onLoad(); GameData.getEquipAffixDataMap().put(id, affix);
        var entry = new OpenConfigData(); entry.$type = "AddAbility"; entry.abilityName = ability;
        GameData.getOpenConfigEntries().put(config, new OpenConfigEntry(config, new OpenConfigData[] {entry}));
    }

    @AfterEach @SuppressWarnings({"rawtypes", "unchecked"}) void restore() {
        backups.forEach((map, values) -> { Map raw = map; raw.clear(); raw.putAll(values); });
        fixture.restore();
    }

    TpsEquipChangeNotify equipmentPacket() throws Exception {
        var packets = fixture.player.packets;
        for (int i = packets.size() - 1; i >= 0; i--) {
            if (packets.get(i).getOpcode() == PacketOpcodes.TpsEquipChangeNotify)
                return TpsEquipChangeNotify.parseFrom(packets.get(i).getData());
        }
        throw new AssertionError("No equipment update emitted");
    }

    void assertWeaponAnnouncements(int expected) throws Exception {
        var announcements = new ArrayList<AbilityChangeNotify>();
        for (var packet : fixture.player.packets) {
            if (packet.getOpcode() != PacketOpcodes.AbilityChangeNotify) continue;
            var change = AbilityChangeNotify.parseFrom(packet.getData());
            if (scene.getWeaponEntities().containsKey(change.getEntityId())) announcements.add(change);
        }
        assertEquals(expected, announcements.size());
        for (var change : announcements) {
            assertEquals(List.of(Utils.abilityHash("TPS_Weapon_Audit_Fire"), Utils.abilityHash("TPS_Weapon_Audit_Reload")),
                change.getAbilityControlBlock().getAbilityEmbryoListList().stream().map(e -> e.getAbilityNameHash()).toList());
        }
    }

    @Test void actualWearTwoOneNonePreservesAffixesPacketsAbilitiesAndSaves() throws Exception {
        var p = fixture.player;
        var ordinary = new MemoryItem(JsonUtils.decode("{\"id\":11001,\"itemType\":\"ITEM_WEAPON\",\"equipType\":\"EQUIP_WEAPON\",\"weaponProperties\":[]}", ItemData.class));
        avatar.getEquips().put(6, ordinary);
        var first = item(224001); var second = item(224002);
        first.getTpsAccessoryIds().addAll(List.of(224505, 999999));
        assertTrue(p.inventory.addItem(first)); assertTrue(p.inventory.addItem(second));
        int firstSaves = first.saves;
        p.packets.clear();
        assertEquals(0, TpsWeaponSystem.wear(p, 1, List.of(first.getGuid(), second.getGuid())));
        assertEquals(27f, avatar.getFightProperty(FightProperty.FIGHT_PROP_ATTACK));
        assertEquals(Set.of("Avatar_TPS_Audit_Base", "Avatar_TPS_Audit_Accessory"), avatar.getExtraAbilityEmbryos());
        var two = equipmentPacket(); assertEquals(2, two.getTpsWeaponListCount());
        assertEquals(Map.of(990001, 0, 990002, 0), two.getTpsWeaponList(0).getAffixMapMap());
        assertEquals(Map.of(990001, 0), two.getTpsWeaponList(1).getAffixMapMap());
        assertWeaponAnnouncements(2); assertEquals(2, scene.getWeaponEntities().size());
        var oldIds = new HashSet<>(scene.getWeaponEntities().keySet());
        p.packets.clear();
        assertEquals(0, TpsWeaponSystem.wear(p, 1, List.of(first.getGuid())));
        assertEquals(17f, avatar.getFightProperty(FightProperty.FIGHT_PROP_ATTACK));
        assertEquals(1, equipmentPacket().getTpsWeaponListCount()); assertWeaponAnnouncements(1);
        assertEquals(1, scene.getWeaponEntities().size());
        assertTrue(Collections.disjoint(oldIds, scene.getWeaponEntities().keySet()));
        assertNull(second.getWeaponEntity());
        p.packets.clear();
        assertEquals(0, TpsWeaponSystem.wear(p, 1, List.of()));
        assertEquals(0f, avatar.getFightProperty(FightProperty.FIGHT_PROP_ATTACK));
        assertTrue(avatar.getExtraAbilityEmbryos().isEmpty());
        assertEquals(0, equipmentPacket().getTpsWeaponListCount()); assertWeaponAnnouncements(0);
        assertTrue(scene.getWeaponEntities().isEmpty()); assertNull(first.getWeaponEntity());
        assertEquals(3, avatar.saves); assertEquals(firstSaves, first.saves);
        assertSame(ordinary, avatar.getWeapon());
        assertEquals(List.of(224505, 999999), first.getTpsAccessoryIds());
        assertEquals(List.of(224505, 999999), TpsWeaponSystem.toTpsWeaponProto(first).getAccessoryIdListList());
    }

    @Test void actualAccessoryGrantRecalculatesAndEmitsSavedAffixAndAbilityUpdates() throws Exception {
        var p = fixture.player; var gun = item(224001); assertTrue(p.inventory.addItem(gun));
        assertEquals(0, TpsWeaponSystem.wear(p, 1, List.of(gun.getGuid())));
        p.packets.clear(); int saves = gun.saves;
        var material = item(223111); assertTrue(p.inventory.addItem(material));
        assertEquals(saves + 1, gun.saves);
        assertEquals(17f, avatar.getFightProperty(FightProperty.FIGHT_PROP_ATTACK));
        assertEquals(Map.of(990001, 0, 990002, 0), equipmentPacket().getTpsWeaponList(0).getAffixMapMap());
        assertWeaponAnnouncements(1);
        assertTrue(p.packets.stream().anyMatch(packet -> packet.getOpcode() == PacketOpcodes.AvatarFightPropNotify));
        var abilityHashes = new HashSet<Integer>();
        for (var packet : p.packets) {
            if (packet.getOpcode() == PacketOpcodes.AbilityChangeNotify) {
                var change = AbilityChangeNotify.parseFrom(packet.getData());
                if (change.getEntityId() == 28) change.getAbilityControlBlock().getAbilityEmbryoListList().forEach(e -> abilityHashes.add(e.getAbilityNameHash()));
            }
        }
        assertTrue(abilityHashes.contains(Utils.abilityHash("Avatar_TPS_Audit_Accessory")));
        boolean stored = false;
        for (var packet : p.packets) {
            if (packet.getOpcode() != PacketOpcodes.StoreItemChangeNotify) continue;
            var update = StoreItemChangeNotify.parseFrom(packet.getData());
            for (var item : update.getItemListList()) {
                if (item.getItemId() == 224001) {
                    stored = true;
                    assertEquals(TpsWeaponSystem.toTpsWeaponProto(gun), item.getTpsWeapon());
                }
            }
        }
        assertTrue(stored, "saved gun update was not emitted");
        p.packets.clear(); assertTrue(p.inventory.removeItem(material, 1));
        assertEquals(List.of(224505), gun.getTpsAccessoryIds());
        assertEquals(17f, avatar.getFightProperty(FightProperty.FIGHT_PROP_ATTACK));
        assertEquals(saves + 1, gun.saves);
    }

    @Test void identicalWearRepairsCompetingOwnerWithoutRetiringTargetsGun() throws Exception {
        var p = fixture.player; var gun = item(224001); assertTrue(p.inventory.addItem(gun));
        assertEquals(0, TpsWeaponSystem.wear(p, 1, List.of(gun.getGuid())));
        var original = gun.getWeaponEntity();
        var competing = fixture.wearer(2, 0); p.avatars.getAvatars().put(2, competing);
        p.packets.clear();
        assertEquals(0, TpsWeaponSystem.wear(p, 1, List.of(gun.getGuid())));
        assertTrue(competing.ids.isEmpty()); assertEquals(1, competing.saves);
        assertSame(original, gun.getWeaponEntity());
        assertEquals(1, scene.getWeaponEntities().size());
        assertEquals(1, avatar.saves);
        assertEquals(2, equipmentPacket().getAvatarGuid());
        assertEquals(0, equipmentPacket().getTpsWeaponListCount());
    }

    @Test void staleDepartureCannotRetireReplacementScenesGun() throws Exception {
        var p = fixture.player; var gun = item(224001); assertTrue(p.inventory.addItem(gun));
        assertEquals(0, TpsWeaponSystem.wear(p, 1, List.of(gun.getGuid())));
        var oldAvatar = p.team.getActiveTeam().get(0);
        var replacementScene = allocate(RecordingScene.class); replacementScene.player = p;
        set(replacementScene, Scene.class, "world", p.world);
        set(replacementScene, Scene.class, "sceneData", JsonUtils.decode("{\"id\":4}", emu.grasscutter.data.excels.scene.SceneData.class));
        set(replacementScene, Scene.class, "weaponEntities", new HashMap<Integer, GameEntity>());
        p.team.getActiveTeam().clear();
        p.team.getActiveTeam().add(TpsReviewRegressionTest.avatarEntity(avatar, replacementScene, 29));
        TpsWeaponSystem.ensureWeaponEntities(avatar, replacementScene);
        var replacement = gun.getWeaponEntity();
        TpsWeaponSystem.retireWeaponEntities(oldAvatar);
        assertSame(replacement, gun.getWeaponEntity());
        assertTrue(scene.getWeaponEntities().isEmpty());
        assertEquals(1, replacementScene.getWeaponEntities().size());
        assertEquals(List.of(224001), avatar.getTpsWeaponIds());
    }

    static final class RecordingScene extends Scene {
        P player;
        RecordingScene() { super(null, null); }
        @Override public void broadcastPacket(BasePacket packet) { player.sendPacket(packet); }
    }

    static final class MemoryAvatar extends Avatar {
        final P player; int saves;
        MemoryAvatar(P player) { super(); this.player = player; }
        @Override public emu.grasscutter.game.player.Player getPlayer() { return player; }
        @Override public int getAvatarId() { return 1; }
        @Override public long getGuid() { return 1; }
        @Override public void save() { saves++; }
        @Override public void recalcStats() {
            assertFalse(Thread.holdsLock(player.getInventory()), "single-item callback acquired a broad lock");
            super.recalcStats();
        }
    }
}
