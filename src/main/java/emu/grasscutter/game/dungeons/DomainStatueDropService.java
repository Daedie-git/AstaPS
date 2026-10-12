package emu.grasscutter.game.dungeons;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.RewardPreviewData;
import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.game.dungeons.DomainStatueClaimHelper.ClaimMode;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.server.packet.send.PacketGadgetAutoPickDropInfoNotify;
import emu.grasscutter.utils.Utils;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Petrified-tree claim implementation that does <b>not</b> replace {@link DungeonManager}.
 * Uses reflection only for the private rewardedPlayers set.
 */
public final class DomainStatueDropService {

    private DomainStatueDropService() {}

    public static boolean claim(Player player, DungeonManager dm, ClaimMode mode, int groupId) {
        if (dm == null) return false;
        synchronized (dm) { return claimLocked(player, dm, mode, groupId); }
    }

    private static boolean claimLocked(Player player, DungeonManager dm, ClaimMode mode, int groupId) {
        if (player == null || dm == null) {
            return false;
        }
        if (mode == null) {
            mode = ClaimMode.NORMAL_1X;
        }
        if (!dm.isFinishedSuccessfully()) {
            Grasscutter.getLogger()
                    .warn("StatueDrop abort: not finished dungeon={}", dm.getDungeonData().getId());
            return false;
        }

        DungeonData dungeonData = dm.getDungeonData();
        IntSet rewarded = getRewardedPlayers(dm);
        if (rewarded != null && rewarded.contains(player.getUid())) {
            Grasscutter.getLogger()
                    .warn(
                            "StatueDrop abort: already rewarded uid={} dungeon={}",
                            player.getUid(),
                            dungeonData.getId());
            return false;
        }

        var preview = dungeonData.getRewardPreviewData();
        if (preview == null && dungeonData.getPassRewardPreviewID() > 0) {
            preview = GameData.getRewardPreviewDataMap().get(dungeonData.getPassRewardPreviewID());
        }
        boolean hasPreview =
                preview != null
                        && preview.getPreviewItems() != null
                        && preview.getPreviewItems().length > 0;
        if (!hasPreview && dungeonData.getStatueDrop() <= 0) {
            Grasscutter.getLogger()
                    .warn(
                            "StatueDrop abort: no preview/statueDrop dungeon={}",
                            dungeonData.getId());
            return false;
        }

        final ClaimMode claimMode = mode;
        final RewardPreviewData resolvedPreview = preview;
        int rollTimes = Math.max(1, mode.rollTimes);
        var claim = emu.grasscutter.game.inventory.ClaimBoundary.reserve(dm, "domain:" + player.getUid());
        if (claim == null) return false;
        boolean[] paid = {false};
        List<GameItem> rewards;
        synchronized (player.getInventory()) {
            rewards = DomainRewardDelivery.grant(() -> {
                DungeonDropLoader.ensureLoaded();
                var generated = buildRewards(player, dm, dungeonData, resolvedPreview, hasPreview, rollTimes);
                if (generated == null) return List.of();
                ReliquaryDomainBonusHelper.appendToRewards(dm, generated);
                generated.removeIf(item -> item == null || item.getCount() <= 0);
                return generated;
            }, items -> emu.grasscutter.game.inventory.ClaimRewardDelivery.canGrant(player.getInventory(), items),
                    () -> paid[0] = payCost(player, dungeonData, claimMode),
                    items -> emu.grasscutter.game.inventory.ClaimRewardDelivery.tryGrant(player.getInventory(), items, ActionReason.DungeonStatueDrop));
        }
        if (rewards.isEmpty()) {
            if (!paid[0]) claim.release(); // A post-payment failure stays quarantined, not retry-safe.
            return false;
        }
        if (rewarded != null) {
            rewarded.add(player.getUid());
            claim.release();
        }
        player.sendPacket(new PacketGadgetAutoPickDropInfoNotify(rewards));

        try {
            dm.getScene()
                    .getScriptManager()
                    .callEvent(new ScriptArgs(groupId, EventType.EVENT_DUNGEON_REWARD_GET));
        } catch (Throwable t) {
            Grasscutter.getLogger().warn("EVENT_DUNGEON_REWARD_GET failed", t);
        }

        Grasscutter.getLogger()
                .info(
                        "StatueDrop ok uid={} dungeon={} mode={} times={} items={}",
                        player.getUid(),
                        dungeonData.getId(),
                        mode,
                        rollTimes,
                        rewards.size());
        return true;
    }

    private static boolean payCost(Player player, DungeonData dungeonData, ClaimMode mode) {
        int baseResinCost =
                dungeonData.getStatueCostCount() != 0 ? dungeonData.getStatueCostCount() : 20;
        return switch (mode) {
            case CONDENSE -> {
                if (baseResinCost != 0 && baseResinCost != 20) {
                    yield false;
                }
                yield player.getResinManager().useCondensedResin(1);
            }
            case FRAGILE -> player.getInventory()
                    .payItem(DomainStatueClaimHelper.FRAGILE_RESIN_ITEM_ID, 1);
            case HCOIN -> player.getResinManager().payHcoinRewardClaim();
            case NORMAL_2X -> {
                if (dungeonData.getStatueCostID() != 0 && dungeonData.getStatueCostID() != 106) {
                    int cost;
                    try { cost = Math.multiplyExact(baseResinCost, 2); }
                    catch (ArithmeticException overflow) { yield false; }
                    yield player.getInventory().payItem(dungeonData.getStatueCostID(), cost);
                }
                yield player.getResinManager().useResin(40);
            }
            case NORMAL_1X -> {
                if (dungeonData.getStatueCostID() != 0 && dungeonData.getStatueCostID() != 106) {
                    yield player.getInventory().payItem(dungeonData.getStatueCostID(), baseResinCost);
                }
                int cost = baseResinCost > 0 ? baseResinCost : 20;
                yield player.getResinManager().useResin(cost);
            }
        };
    }

    private static List<GameItem> buildRewards(
            Player player,
            DungeonManager dm,
            DungeonData dungeonData,
            RewardPreviewData preview,
            boolean hasPreview,
            int rollTimes) {
        List<GameItem> rewards = null;
        int dungeonId = dungeonData.getId();
        boolean hasDungeonDrop =
                GameData.getDungeonDropDataMap() != null
                        && GameData.getDungeonDropDataMap().containsKey(dungeonId);
        if (hasDungeonDrop) {
            rewards = rollDungeonDropJson(dm, dungeonData, rollTimes);
        }
        int statueDrop = dungeonData.getStatueDrop();
        if ((rewards == null || rewards.isEmpty()) && statueDrop > 0) {
            rewards = rollStatueDropTable(player, statueDrop, rollTimes);
        }
        if ((rewards == null || rewards.isEmpty()) && hasDungeonDrop) {
            rewards = rollDungeonDropJson(dm, dungeonData, rollTimes);
        }
        if ((rewards == null || rewards.isEmpty()) && hasPreview) {
            rewards = new ArrayList<>();
            for (ItemParamData param : preview.getPreviewItems()) {
                if (param != null && param.getId() > 0 && param.getCount() > 0) {
                    rewards.add(emu.grasscutter.game.inventory.ClaimRewardDelivery.declared(param.getId(), Math.multiplyExact(param.getCount(), rollTimes)));
                }
            }
        }
        return rewards;
    }

    private static List<GameItem> rollStatueDropTable(Player player, int statueDrop, int rollTimes) {
        if (rollTimes <= 1) {
            return player.getServer().getDropSystem().handleDungeonRewardDrop(statueDrop, false);
        }
        if (rollTimes == 2) {
            return player.getServer().getDropSystem().handleDungeonRewardDrop(statueDrop, true);
        }
        List<GameItem> rewards = new ArrayList<>();
        List<GameItem> first =
                player.getServer().getDropSystem().handleDungeonRewardDrop(statueDrop, true);
        List<GameItem> extra =
                player.getServer().getDropSystem().handleDungeonRewardDrop(statueDrop, false);
        if (first != null) {
            rewards.addAll(first);
        }
        if (extra != null) {
            rewards.addAll(extra);
        }
        return rewards;
    }

    private static List<GameItem> rollDungeonDropJson(
            DungeonManager dm, DungeonData dungeonData, int rollTimes) {
        int times = Math.max(1, rollTimes);
        List<GameItem> rewards = new ArrayList<>();
        int dungeonId = dungeonData.getId();
        if (!GameData.getDungeonDropDataMap().containsKey(dungeonId)) {
            return rewards;
        }
        List<DungeonDropEntry> dropEntries = GameData.getDungeonDropDataMap().get(dungeonId);
        for (var entry : dropEntries) {
            int start = entry.getCounts().get(0);
            int end = entry.getCounts().get(entry.getCounts().size() - 1);
            var candidateAmounts = IntStream.range(start, end + 1).boxed().collect(Collectors.toList());

            int amount = 0;
            for (int t = 0; t < times; t++) {
                amount = Math.addExact(amount, Utils.drawRandomListElement(candidateAmounts, entry.getProbabilities()));
            }
            if (entry.isMpDouble() && dm.getScene().getPlayerCount() > 1) {
                amount = Math.multiplyExact(amount, 2);
            }
            if (entry.getItems().size() == 1) {
                rewards.add(emu.grasscutter.game.inventory.ClaimRewardDelivery.declared(entry.getItems().get(0), amount));
            } else {
                for (int i = 0; i < amount; i++) {
                    int itemId =
                            Utils.drawRandomListElement(entry.getItems(), entry.getItemProbabilities());
                    rewards.add(new GameItem(itemId, 1));
                }
            }
        }
        return rewards;
    }

    @SuppressWarnings("unchecked")
    private static IntSet getRewardedPlayers(DungeonManager dm) {
        try {
            Field field = DungeonManager.class.getDeclaredField("rewardedPlayers");
            field.setAccessible(true);
            return (IntSet) field.get(dm);
        } catch (Throwable t) {
            Grasscutter.getLogger().warn("StatueDrop: cannot access rewardedPlayers", t);
            return null;
        }
    }
}
