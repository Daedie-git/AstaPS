package emu.grasscutter.game.home;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.HomeworldDefaultSaveData;
import emu.grasscutter.game.entity.EntityHomeAnimal;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.HomeSceneArrangementInfoOuterClass.HomeSceneArrangementInfo;
import java.util.Collection;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Entity
@Data
@Builder(builderMethodName = "of")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class HomeSceneItem {
    @Id int sceneId;
    Map<Integer, HomeBlockItem> blockItems;
    Position bornPos;
    Position bornRot;
    Position djinnPos;
    int homeBgmId;
    HomeFurnitureItem mainHouse;
    int tmpVersion;

    public static HomeSceneItem parseFrom(HomeworldDefaultSaveData defaultItem, int sceneId) {
        return HomeSceneItem.of()
                .sceneId(sceneId)
                .blockItems(
                        defaultItem.getHomeBlockLists().stream()
                                .map(HomeBlockItem::parseFrom)
                                .collect(Collectors.toMap(HomeBlockItem::getBlockId, y -> y)))
                .bornPos(defaultItem.getBornPos())
                .bornRot(defaultItem.getBornRot() == null ? new Position() : defaultItem.getBornRot())
                .djinnPos(defaultItem.getDjinPos() == null ? new Position() : defaultItem.getDjinPos())
                .mainHouse(
                        defaultItem.getMainhouse() == null
                                ? null
                                : HomeFurnitureItem.parseFrom(defaultItem.getMainhouse()))
                .build();
    }

    public boolean validateArrangement(HomeSceneArrangementInfo candidate, Player owner) {
        if (owner == null || owner.getInventory() == null) return false;
        Set<Integer> blocks = new HashSet<>();
        Set<Integer> guids = new HashSet<>();
        Map<Integer, Integer> counts = new HashMap<>();
        for (var block : candidate.getBlockArrangementInfoListList()) {
            var existing = this.blockItems.get(block.getBlockId());
            if (!blocks.add(block.getBlockId()) || existing == null || !existing.isUnlocked()) return false;
            for (var furniture : block.getDeployFurniureListList()) {
                var data = emu.grasscutter.data.GameData.getItemDataMap().get(furniture.getFurnitureId());
                if (data == null || data.getItemType() != emu.grasscutter.game.inventory.ItemType.ITEM_FURNITURE
                        || furniture.getGuid() <= 0 || !guids.add(furniture.getGuid())) return false;
                counts.merge(furniture.getFurnitureId(), 1, Integer::sum);
            }
            for (var animal : block.getDeployAnimalListList()) {
                if (!GameData.getHomeWorldAnimalDataMap().containsKey(animal.getFurnitureId())
                        || !GameData.getItemDataMap().containsKey(animal.getFurnitureId())) return false;
                counts.merge(animal.getFurnitureId(), 1, Integer::sum);
            }
            // Persistent defaults may move but may not manufacture additional identities.
            Map<Integer, Integer> persistent = new HashMap<>();
            if (existing.getPersistentFurnitureList() != null) existing.getPersistentFurnitureList()
                    .forEach(item -> persistent.merge(item.getFurnitureId(), 1, Integer::sum));
            for (var furniture : block.getPersistentFurnitureListList()) {
                int remaining = persistent.getOrDefault(furniture.getFurnitureId(), 0) - 1;
                if (remaining < 0) return false;
                persistent.put(furniture.getFurnitureId(), remaining);
            }
            for (var suite : block.getFurnitureSuiteListList()) {
                Set<Integer> members = new HashSet<>();
                for (int index : suite.getIncludedFurnitureIndexListList()) {
                    if (index < 0 || index > block.getDeployFurniureListCount() || !members.add(index)) return false;
                }
            }
            for (var npc : block.getDeployNpcListList()) {
                if (owner.getAvatars() == null || owner.getAvatars().getAvatarById(npc.getAvatarId()) == null) return false;
            }
        }
        for (var entry : this.blockItems.entrySet()) {
            if (blocks.contains(entry.getKey())) continue;
            if (entry.getValue().getDeployAnimalList() != null) for (var item : entry.getValue().getDeployAnimalList()) {
                counts.merge(item.getFurnitureId(), 1, Integer::sum);
            }
            if (entry.getValue().getDeployFurnitureList() == null) continue;
            for (var item : entry.getValue().getDeployFurnitureList()) {
                if (item.getGuid() > 0 && !guids.add(item.getGuid())) return false;
                counts.merge(item.getFurnitureId(), 1, Integer::sum);
            }
        }
        if (candidate.hasMainHouse()) {
            var house = candidate.getMainHouse();
            var row = GameData.getItemDataMap().get(house.getFurnitureId());
            if (this.isRoom() || row == null
                    || row.getItemType() != emu.grasscutter.game.inventory.ItemType.ITEM_FURNITURE
                    || row.getSpecialFurnitureType() != SpecialFurnitureType.Apartment
                    || row.getRoomSceneId() <= 0
                    || !GameData.getHomeworldDefaultSaveData().containsKey(row.getRoomSceneId())) return false;
            boolean preserved = mainHouse.getFurnitureId() == house.getFurnitureId()
                    && mainHouse.getGuid() == house.getGuid() && isDefaultHouse(mainHouse);
            if (preserved && house.getGuid() > 0 && !guids.add(house.getGuid())) return false;
            if (!preserved) {
                if (house.getGuid() <= 0 || !guids.add(house.getGuid())) return false;
                counts.merge(house.getFurnitureId(), 1, Integer::sum);
            }
        } else if (mainHouse != null && !isDefaultHouse(mainHouse)) {
            counts.merge(mainHouse.getFurnitureId(), 1, Integer::sum);
        }
        var home = owner.getHome();
        if (home != null) {
            int outdoorId = owner.getCurrentRealmId() + 2000;
            var counterpart = this.isRoom()
                    ? (home.getSceneMap() == null ? null : home.getSceneMap().get(outdoorId))
                    : (home.getMainHouseMap() == null ? null : home.getMainHouseMap().get(outdoorId));
            if (counterpart != null && counterpart != this && counterpart.getBlockItems() != null) {
                counterpart.getBlockItems().values().forEach(block -> {
                    if (block.getDeployFurnitureList() != null) block.getDeployFurnitureList()
                            .forEach(item -> counts.merge(item.getFurnitureId(), 1, Integer::sum));
                    if (block.getDeployAnimalList() != null) block.getDeployAnimalList()
                            .forEach(item -> counts.merge(item.getFurnitureId(), 1, Integer::sum));
                });
                if (counterpart.getMainHouse() != null && !counterpart.isDefaultHouse(counterpart.getMainHouse()))
                    counts.merge(counterpart.getMainHouse().getFurnitureId(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().allMatch(entry -> entry.getValue() <= owner.getInventory().getItemCountById(entry.getKey()));
    }

    private boolean isDefaultHouse(HomeFurnitureItem house) {
        var defaults = GameData.getHomeworldDefaultSaveData().get(sceneId);
        return defaults != null && defaults.getMainhouse() != null
                && defaults.getMainhouse().getId() == house.getFurnitureId();
    }

    public void update(HomeSceneArrangementInfo arrangementInfo, Player owner) {
        if (!validateArrangement(arrangementInfo, owner)) return;
        for (var blockItem : arrangementInfo.getBlockArrangementInfoListList()) {
            var block = this.blockItems.get(blockItem.getBlockId());
            if (block == null) {
                Grasscutter.getLogger().warn("Could not found the Home Block {}", blockItem.getBlockId());
                continue;
            }
            block.update(blockItem, owner);
            this.blockItems.put(blockItem.getBlockId(), block);
        }

        this.bornPos = new Position(arrangementInfo.getBornPos());
        // born_rot is one of four unnamed Vectors in 7.0's HomeSceneArrangementInfo.
        this.bornRot = new Position();
        this.djinnPos = new Position(arrangementInfo.getDjinnPos());
        this.homeBgmId = arrangementInfo.getBgmId();

        if (!this.isRoom() && arrangementInfo.hasMainHouse()) {
            this.mainHouse = HomeFurnitureItem.parseFrom(arrangementInfo.getMainHouse());
        }

        this.tmpVersion = arrangementInfo.getTmpVersion();
    }

    public int getRoomSceneId() {
        if (this.isRoom()) {
            return 0;
        }
        return mainHouse.getAsItem().getRoomSceneId();
    }

    public boolean isRoom() {
        return mainHouse == null || mainHouse.getAsItem() == null;
    }

    @Nullable public Position getTeleportPointPos(int guid) {
        return this.getBlockItems().values().stream()
                .map(HomeBlockItem::getDeployFurnitureList)
                .flatMap(Collection::stream)
                .filter(homeFurnitureItem -> homeFurnitureItem.getGuid() == guid)
                .map(HomeFurnitureItem::getSpawnPos)
                .findFirst()
                .orElse(null);
    }

    public List<EntityHomeAnimal> getAnimals(Scene scene) {
        return this.blockItems.values().stream()
                .map(HomeBlockItem::getDeployAnimalList)
                .flatMap(Collection::stream)
                .filter(
                        homeAnimalItem ->
                                GameData.getHomeWorldAnimalDataMap().containsKey(homeAnimalItem.getFurnitureId()))
                .map(
                        homeAnimalItem -> {
                            return new EntityHomeAnimal(
                                    scene,
                                    GameData.getHomeWorldAnimalDataMap().get(homeAnimalItem.getFurnitureId()),
                                    homeAnimalItem.getSpawnPos(),
                                    homeAnimalItem.getSpawnRot());
                        })
                .toList();
    }

    public int calComfort() {
        return this.blockItems.values().stream().mapToInt(HomeBlockItem::calComfort).sum();
    }

    public HomeSceneArrangementInfo toProto() {
        var proto = HomeSceneArrangementInfo.newBuilder();
        blockItems.values().forEach(b -> proto.addBlockArrangementInfoList(b.toProto()));

        proto
                .setComfortValue(calComfort())
                .setBornPos(bornPos.toProto())
                .setDjinnPos(djinnPos.toProto())
                .setSceneId(sceneId)
                .setBgmId(homeBgmId)
                .setTmpVersion(tmpVersion);

        if (mainHouse != null) {
            proto.setMainHouse(mainHouse.toProto());
        }
        return proto.build();
    }
}
