package emu.grasscutter.game.inventory;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.game.props.ActionReason;

import java.util.*;

/** Claim caller policy: capacity-safe ordinary delivery; legacy effects remain nontransactional. */
public final class ClaimRewardDelivery {
    private ClaimRewardDelivery() {}

    private static boolean isLegacyEffect(GameItem item) {
        var data = item.getItemData();
        if (data.isUseOnGain()) return true;
        var material = data.getMaterialType();
        return (item.getItemType() == ItemType.ITEM_VIRTUAL
                        && (item.getItemId() < 201 || item.getItemId() > 204))
                || material == MaterialType.MATERIAL_AVATAR
                || material == MaterialType.MATERIAL_FLYCLOAK
                || material == MaterialType.MATERIAL_COSTUME
                || material == MaterialType.MATERIAL_NAMECARD;
    }

    public static boolean canGrant(Inventory inventory, Collection<GameItem> items) {
        if (items == null || items.isEmpty()) return false;
        for (var item : items)
            if (item == null || item.getItemData() == null || item.getCount() <= 0) return false;
        return inventory.canAddItems(items.stream().filter(item -> !isLegacyEffect(item)).toList());
    }

    public static boolean tryGrant(
            Inventory inventory, Collection<GameItem> items, ActionReason reason) {
        synchronized (inventory) {
            if (items == null || items.isEmpty()) return false;
            if (items.stream()
                    .anyMatch(
                            item ->
                                    item == null
                                            || item.getItemData() == null
                                            || item.getCount() <= 0)) return false;
            if (items.stream().noneMatch(ClaimRewardDelivery::isLegacyEffect))
                return inventory.tryAddItems(items, reason);
            if (!canGrant(inventory, items)) return false;
            var ordinary = items.stream().filter(item -> !isLegacyEffect(item)).toList();
            if (!ordinary.isEmpty() && !inventory.tryAddItems(ordinary, reason)) return false;
            // Effects alone retain the nontransactional legacy contract.
            inventory.addItems(items.stream().filter(ClaimRewardDelivery::isLegacyEffect).toList(), reason);
            return true;
        }
    }

    /** Preserve declared quantities for ordinary preflight and equipment splitting. */
    public static GameItem declared(emu.grasscutter.data.excels.ItemData data, int count) {
        var item = new GameItem(data, count);
        item.setCount(count);
        return item;
    }

    public static GameItem declared(int id, int count) {
        return declared(GameData.getItemDataMap().get(id), count);
    }

    public static boolean tryGrantParams(
            Inventory inventory, Collection<ItemParamData> params, ActionReason reason) {
        if (params == null || params.isEmpty()) return false;
        List<GameItem> items = new ArrayList<>();
        boolean legacy = false;
        for (var param : params) {
            if (param == null || param.getCount() <= 0) return false;
            var data = GameData.getItemDataMap().get(param.getId());
            if (data == null) return false;
            var item = new GameItem(data, param.getCount());
            item.setCount(param.getCount());
            items.add(item);
            legacy |= isLegacyEffect(item);
        }
        return legacy
                ? tryGrant(inventory, items, reason)
                : inventory.tryAddItemParamDatas(params, reason);
    }
}
