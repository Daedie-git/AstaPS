package emu.grasscutter.game.home;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.proto.HomeBlockArrangementInfoOuterClass.HomeBlockArrangementInfo;
import emu.grasscutter.net.proto.HomeFurnitureDataOuterClass.HomeFurnitureData;
import emu.grasscutter.net.proto.HomeSceneArrangementInfoOuterClass.HomeSceneArrangementInfo;

import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.*;

class HomeArrangementOwnershipTest {
    @Test
    void unownedFurnitureDoesNotReplaceArrangement() throws Exception {
        Field u = Unsafe.class.getDeclaredField("theUnsafe");
        u.setAccessible(true);
        var p = (FakePlayer) ((Unsafe) u.get(null)).allocateInstance(FakePlayer.class);
        p.inv = (FakeInventory) ((Unsafe) u.get(null)).allocateInstance(FakeInventory.class);
        var block =
                HomeBlockItem.of()
                        .blockId(1)
                        .unlocked(true)
                        .deployFurnitureList(List.of())
                        .persistentFurnitureList(List.of())
                        .deployAnimalList(List.of())
                        .deployNPCList(List.of())
                        .suiteList(List.of())
                        .build();
        var scene = HomeSceneItem.of().blockItems(new HashMap<>(Map.of(1, block))).build();
        var item =
                new Gson()
                        .fromJson(
                                "{\"id\":991770,\"itemType\":\"ITEM_FURNITURE\",\"comfort\":100}",
                                ItemData.class);
        var old = GameData.getItemDataMap().put(991770, item);
        var candidate =
                HomeSceneArrangementInfo.newBuilder()
                        .addBlockArrangementInfoList(
                                HomeBlockArrangementInfo.newBuilder()
                                        .setBlockId(1)
                                        .addDeployFurniureList(
                                                HomeFurnitureData.newBuilder()
                                                        .setFurnitureId(991770)
                                                        .setGuid(1)))
                        .build();
        try {
            scene.update(candidate, p);
            assertTrue(block.getDeployFurnitureList().isEmpty());
            assertEquals(0, block.calComfort());
        } finally {
            if (old == null) GameData.getItemDataMap().remove(991770);
            else GameData.getItemDataMap().put(991770, old);
        }
    }

    static class FakePlayer extends Player {
        FakeInventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static class FakeInventory extends Inventory {
        FakeInventory() {
            super(null);
        }

        @Override
        public int getItemCountById(int id) {
            return 0;
        }
    }
}
