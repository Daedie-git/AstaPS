package emu.grasscutter.game.battlepass;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.common.ItemUseData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.RewardData;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.inventory.MaterialType;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.props.ItemUseOp;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolve MATERIAL_SELECTABLE_CHEST BP rewards into real inventory items.
 *
 * <p>Only the loaded chest's declared choices authorize delivery. Extended grouped choices require
 * an authoritative resource mapping; global ID adjacency is not such a mapping.
 */
public final class BattlePassSelectChestHelper {
    private BattlePassSelectChestHelper() {}

    public static boolean isSelectableReward(int rewardId) {
        return chestItemData(rewardId) != null;
    }

    public static ItemData chestItemData(int rewardId) {
        RewardData reward = GameData.getRewardDataMap().get(rewardId);
        if (reward == null || reward.getRewardItemList() == null) {
            return null;
        }
        for (ItemParamData ip : reward.getRewardItemList()) {
            if (ip == null || ip.getItemId() <= 0) {
                continue;
            }
            ItemData item = GameData.getItemDataMap().get(ip.getItemId());
            if (item != null && item.getMaterialType() == MaterialType.MATERIAL_SELECTABLE_CHEST) {
                return item;
            }
        }
        return null;
    }

    /**
     * Grant the chosen entry from a selectable BP chest.
     *
     * @param optionIdx 1-based slot id from the client UI / GroupedItemSelection
     * @return granted game items (empty on failure)
     */
    public static List<GameItem> grant(Player player, int rewardId, int optionIdx, boolean paid) {
        List<GameItem> granted = new ArrayList<>();
        if (player == null || rewardId <= 0 || optionIdx < 1) {
            return granted;
        }
        ItemData chest = chestItemData(rewardId);
        if (chest == null) {
            Grasscutter.getLogger().warn("TakeBP select: reward {} is not a selectable chest", rewardId);
            return granted;
        }
        ItemUseData use = firstUse(chest);
        if (use == null || use.getUseParam() == null || use.getUseParam().length < 1) {
            Grasscutter.getLogger().warn("TakeBP select: chest {} has no useParam", chest.getId());
            return granted;
        }

        int chosen = resolveChoiceRewardOrItem(chest.getId(), use, optionIdx);
        if (chosen <= 0) {
            return granted;
        }

        ItemUseOp op = use.getUseOp();
        Inventory inv = player.getInventory();
        ActionReason reason = paid ? ActionReason.BattlePassPaidReward : ActionReason.BattlePassLevelReward;

        if (op == ItemUseOp.ITEM_USE_ADD_SELECT_ITEM) {
            ItemData itemData = GameData.getItemDataMap().get(chosen);
            if (itemData == null) {
                Grasscutter.getLogger().warn("TakeBP select: missing item {}", chosen);
                return granted;
            }
            int count = 1;
            RewardData outer = GameData.getRewardDataMap().get(rewardId);
            if (outer != null && outer.getRewardItemList() != null) {
                for (ItemParamData ip : outer.getRewardItemList()) {
                    if (ip != null && ip.getItemId() == chest.getId() && ip.getItemCount() > 0) {
                        count = ip.getItemCount();
                        break;
                    }
                }
            }
            GameItem gi = emu.grasscutter.game.inventory.ClaimRewardDelivery.declared(itemData, count);
            if (!emu.grasscutter.game.inventory.ClaimRewardDelivery.tryGrant(inv, List.of(gi), reason)) return granted;
            granted.add(gi);
            Grasscutter.getLogger()
                    .info(
                            "TakeBP select ADD_ITEM uid={} chest={} choiceItem={} x{}",
                            player.getUid(),
                            chest.getId(),
                            chosen,
                            count);
            return granted;
        }

        if (op == ItemUseOp.ITEM_USE_GRANT_SELECT_REWARD) {
            RewardData chosenReward = GameData.getRewardDataMap().get(chosen);
            if (chosenReward == null || chosenReward.getRewardItemList() == null) {
                Grasscutter.getLogger().warn("TakeBP select: missing reward {}", chosen);
                return granted;
            }
            for (ItemParamData ip : chosenReward.getRewardItemList()) {
                if (ip == null || ip.getItemId() <= 0 || ip.getItemCount() <= 0) {
                    continue;
                }
                ItemData itemData = GameData.getItemDataMap().get(ip.getItemId());
                if (itemData == null) {
                    Grasscutter.getLogger()
                            .warn("TakeBP select: missing item {} from reward {}", ip.getItemId(), chosen);
                    return List.of();
                }
                GameItem gi = emu.grasscutter.game.inventory.ClaimRewardDelivery.declared(itemData, ip.getItemCount());
                granted.add(gi);
            }
            if (!emu.grasscutter.game.inventory.ClaimRewardDelivery.tryGrant(inv, granted, reason)) return List.of();
            Grasscutter.getLogger()
                    .info(
                            "TakeBP select GRANT_REWARD uid={} chest={} choiceReward={} optionIdx={} items={}",
                            player.getUid(),
                            chest.getId(),
                            chosen,
                            optionIdx,
                            granted.size());
            return granted;
        }

        Grasscutter.getLogger()
                .error(
                        "TakeBP select: unsupported useOp {} on chest {}",
                        op,
                        chest.getId());
        return granted;
    }

    /**
     * Map client option_idx to a reward id (GRANT_SELECT) or item id (ADD_SELECT).
     */
    private static int resolveChoiceRewardOrItem(int chestId, ItemUseData use, int optionIdx) {
        String raw = use.getUseParam()[0];
        if (raw == null || raw.isBlank()) {
            Grasscutter.getLogger().warn("TakeBP select: chest {} empty useParam[0]", chestId);
            return -1;
        }
        String[] parts = raw.split(",");
        List<Integer> choices = new ArrayList<>(parts.length + 16);
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v <= 0) return -1;
                choices.add(v);
            } catch (Exception ignored) {
                return -1;
            }
        }
        if (choices.isEmpty()) {
            Grasscutter.getLogger().warn("TakeBP select: chest {} no parseable choices", chestId);
            return -1;
        }

        return optionIdx >= 1 && optionIdx <= choices.size() ? choices.get(optionIdx - 1) : -1;
    }

    private static ItemUseData firstUse(ItemData chest) {
        if (chest.getItemUse() == null) {
            return null;
        }
        for (ItemUseData u : chest.getItemUse()) {
            if (u == null || u.getUseOp() == null) {
                continue;
            }
            if (u.getUseOp() == ItemUseOp.ITEM_USE_NONE) {
                continue;
            }
            return u;
        }
        return null;
    }
}
