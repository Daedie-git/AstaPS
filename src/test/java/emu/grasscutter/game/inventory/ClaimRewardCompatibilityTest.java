package emu.grasscutter.game.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;

import emu.grasscutter.game.props.ActionReason;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

class ClaimRewardCompatibilityTest {
    @ParameterizedTest
    @CsvSource({
        "106,ITEM_VIRTUAL,MATERIAL_NONE,false",
        "105,ITEM_VIRTUAL,MATERIAL_NONE,false",
        "991801,ITEM_MATERIAL,MATERIAL_NAMECARD,false",
        "991802,ITEM_MATERIAL,MATERIAL_COSTUME,false",
        "991803,ITEM_MATERIAL,MATERIAL_FLYCLOAK,false",
        "991804,ITEM_MATERIAL,MATERIAL_NONE,true"
    })
    void knownLegacyEffectKeepsBaselineClaimBehavior(
            int id, String type, String material, boolean useOnGain) throws Exception {
        var inventory = inventory();
        var effect = item(id, type, material, useOnGain);
        assertFalse(inventory.tryAddItems(List.of(effect), ActionReason.PlayerUpgradeReward));
        assertTrue(
                ClaimRewardDelivery.tryGrant(
                        inventory, List.of(effect), ActionReason.PlayerUpgradeReward));
        assertEquals(1, inventory.legacyDeliveries);
    }

    @Test
    void mixedClaimChecksOrdinaryCapacityBeforeAnyLegacyEffect() throws Exception {
        var inventory = inventory();
        inventory.full = true;
        var rewards =
                List.of(
                        item(991810, "ITEM_WEAPON", "MATERIAL_NONE", false),
                        item(106, "ITEM_VIRTUAL", "MATERIAL_NONE", false));
        assertFalse(
                ClaimRewardDelivery.tryGrant(inventory, rewards, ActionReason.DungeonStatueDrop));
        assertEquals(0, inventory.legacyDeliveries);
        inventory.full = false;
        assertTrue(
                ClaimRewardDelivery.tryGrant(inventory, rewards, ActionReason.DungeonStatueDrop));
        assertEquals(1, inventory.legacyDeliveries);
    }

    private static RecordingInventory inventory() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (RecordingInventory)
                ((sun.misc.Unsafe) field.get(null)).allocateInstance(RecordingInventory.class);
    }

    private static GameItem item(int id, String type, String material, boolean useOnGain) {
        var data =
                new Gson()
                        .fromJson(
                                "{\"id\":"
                                        + id
                                        + ",\"itemType\":\""
                                        + type
                                        + "\",\"materialType\":\""
                                        + material
                                        + "\",\"useOnGain\":"
                                        + useOnGain
                                        + "}",
                                emu.grasscutter.data.excels.ItemData.class);
        var item = new GameItem(data, 1);
        item.setCount(1);
        return item;
    }

    private static class RecordingInventory extends Inventory {
        int legacyDeliveries;
        boolean full;

        RecordingInventory() {
            super(null);
        }

        @Override
        public boolean canAddItems(Collection<GameItem> items) {
            if (full && !items.isEmpty()) return false;
            return items.stream()
                    .noneMatch(
                            item ->
                                    item.getItemData().isUseOnGain()
                                            || (item.getItemType() == ItemType.ITEM_VIRTUAL
                                                    && item.getItemId() < 201)
                                            || item.getItemData().getMaterialType()
                                                    == MaterialType.MATERIAL_NAMECARD
                                            || item.getItemData().getMaterialType()
                                                    == MaterialType.MATERIAL_COSTUME
                                            || item.getItemData().getMaterialType()
                                                    == MaterialType.MATERIAL_FLYCLOAK);
        }

        @Override
        public boolean tryAddItems(Collection<GameItem> items, ActionReason reason) {
            return canAddItems(items);
        }

        @Override
        public InventoryAddResult addItems(Collection<GameItem> items, ActionReason reason) {
            legacyDeliveries++;
            return emu.grasscutter.audit.InventoryResults.accepted(items);
        }
    }
}
