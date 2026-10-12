package emu.grasscutter.server.packet.recv;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.avatar.AvatarCostumeData;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.shop.*;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.BuyGoodsReqOuterClass;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketBuyGoodsRsp;
import emu.grasscutter.utils.Utils;
import java.util.*;

@Opcodes(PacketOpcodes.BuyGoodsReq)
public class HandlerBuyGoodsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        BuyGoodsReqOuterClass.BuyGoodsReq buyGoodsReq =
                BuyGoodsReqOuterClass.BuyGoodsReq.parseFrom(payload);
        List<ShopInfo> configShop =
                session.getServer().getShopSystem().getShopData().get(buyGoodsReq.getShopType());
        if (configShop == null) {
            session.send(new PacketBuyGoodsRsp(Retcode.RET_SVR_ERROR));
            return;
        }

        // Don't trust your users' input
        var player = session.getPlayer();

        // A non-positive count buys nothing and cannot be honest. Worse, it inverts the whole
        // purchase: payItems checks `held < cost * count`, which is never true for a negative
        // count, and payVirtualItem then subtracts that negative - handing out mora and
        // primogems instead of taking them.
        int buyCount = buyGoodsReq.getBuyCount();
        if (buyCount <= 0) {
            session.send(new PacketBuyGoodsRsp(Retcode.RET_SVR_ERROR));
            return;
        }

        List<Integer> targetShopGoodsId = List.of(buyGoodsReq.getGoods().getGoodsId());
        for (int goodsId : targetShopGoodsId) {
            Optional<ShopInfo> sg2 =
                    configShop.stream().filter(x -> x.getGoodsId() == goodsId).findFirst();
            if (sg2.isEmpty()) {
                session.send(new PacketBuyGoodsRsp(Retcode.RET_SVR_ERROR));
                continue;
            }
            ShopInfo sg = sg2.get();
            int itemId = sg.getGoodsItem().getId();
            AvatarCostumeData costumeData =
                    GameData.getAvatarCostumeDataItemIdMap().get(itemId);

            // Already unlocked costume: refuse repurchase (client may still send BuyGoods).
            if (costumeData != null
                    && player.getCostumeList() != null
                    && player.getCostumeList().contains(costumeData.getId())) {
                int soldOut = Math.max(1, sg.getBuyLimit());
                ShopLimit ownedLimit = player.getGoodsLimit(sg.getGoodsId());
                if (ownedLimit == null) {
                    player.addShopLimit(sg.getGoodsId(), soldOut, 0);
                } else {
                    ownedLimit.setHasBoughtInPeriod(
                            Math.max(ownedLimit.getHasBoughtInPeriod(), soldOut));
                    ownedLimit.setHasBought(Math.max(ownedLimit.getHasBought(), soldOut));
                    ownedLimit.setNextRefreshTime(0);
                }
                session.send(new PacketBuyGoodsRsp(Retcode.RET_SHOP_BATCH_BUY_COUNT_LIMIT));
                continue;
            }

            int currentTs = Utils.getCurrentSeconds();
            boolean refreshes = sg.getShopRefreshType() != ShopInfo.ShopRefreshType.NONE;
            ShopLimit shopLimit = player.getGoodsLimit(sg.getGoodsId());
            boolean expired = shopLimit != null && refreshes
                    && shopLimit.getNextRefreshTime() > 0
                    && currentTs >= shopLimit.getNextRefreshTime();
            int bought = shopLimit == null || expired ? 0 : shopLimit.getHasBoughtInPeriod();

            if (((long) bought + buyCount > sg.getBuyLimit()) && sg.getBuyLimit() != 0) {
                session.send(new PacketBuyGoodsRsp(Retcode.RET_SHOP_BATCH_BUY_COUNT_LIMIT));
                continue;
            }

            var artifactShop = session.getServer().getShopSystem().getArtifactShop();
            var piece = artifactShop.getPiece(sg.getGoodsId());
            if (piece != null) {
                // Artifacts do not stack, so a batch buy needs that many free slots. Asking before
                // the payment keeps a full bag from swallowing the mora and handing back nothing.
                var relics = player.getInventory().getInventoryTab(ItemType.ITEM_RELIQUARY);
                if (buyCount > relics.getMaxCapacity() - relics.getSize()) {
                    session.send(new PacketBuyGoodsRsp(Retcode.RET_PACK_EXCEED_MAX_WEIGHT));
                    continue;
                }
            }

            int itemCount;
            try {
                itemCount = Math.multiplyExact(buyCount, sg.getGoodsItem().getCount());
                Math.addExact(bought, buyCount);
                if (shopLimit != null) Math.addExact(shopLimit.getHasBought(), buyCount);
            } catch (ArithmeticException overflow) {
                session.send(new PacketBuyGoodsRsp(Retcode.RET_SVR_ERROR));
                continue;
            }
            List<GameItem> grants = new ArrayList<>();
            if (piece != null) {
                for (int i = 0; i < buyCount; i++) grants.add(artifactShop.roll(piece));
            } else {
                if (GameData.getItemDataMap().get(itemId) == null) {
                    session.send(new PacketBuyGoodsRsp(Retcode.RET_SVR_ERROR));
                    continue;
                }
                var output = new GameItem(itemId, itemCount);
                output.setCount(itemCount);
                grants.add(output);
            }
            // Shop effect goods retain the legacy single-item policy (not a claim batch).
            boolean effectGood = grants.size() == 1 && grants.get(0).getItemData() != null
                    && grants.get(0).getItemData().isUseOnGain();
            List<ItemParamData> costs =
                    new ArrayList<>(
                            sg.getCostItemList() != null
                                    ? sg.getCostItemList()
                                    : Collections.emptyList());
            costs.add(new ItemParamData(202, sg.getScoin()));
            costs.add(new ItemParamData(201, sg.getHcoin()));
            costs.add(new ItemParamData(203, sg.getMcoin()));
            if (effectGood && !player.getInventory().payItems(costs, buyCount)) {
                session.send(new PacketBuyGoodsRsp(Retcode.RET_SHOP_CONTENT_NOT_MATCH));
                continue;
            }

            int nextRefresh = refreshes ? ShopSystem.getShopNextRefreshTime(sg) : 0;
            boolean delivered = effectGood
                    ? player.getInventory().addItem(grants.get(0), ActionReason.Shop, true)
                    : player.getInventory().tryPayAndAddItems(costs, buyCount, grants, ActionReason.Shop);
            if (!delivered) {
                session.send(new PacketBuyGoodsRsp(Retcode.RET_PACK_EXCEED_MAX_WEIGHT));
                continue;
            }
            if (expired) shopLimit.setHasBoughtInPeriod(0);
            player.addShopLimit(sg.getGoodsId(), buyCount, nextRefresh);

            // Costume materials use useOnGain; also unlock directly if inventory path skipped it.
            if (costumeData != null
                    && (player.getCostumeList() == null
                            || !player.getCostumeList().contains(costumeData.getId()))) {
                player.addCostume(costumeData.getId());
            }

            // Return full server-side goods (buy_limit / next_refresh), not the client stub.
            var limit = player.getGoodsLimit(sg.getGoodsId());
            session.send(
                    new PacketBuyGoodsRsp(
                            buyGoodsReq.getShopType(),
                            limit.getHasBoughtInPeriod(),
                            sg,
                            refreshes ? limit.getNextRefreshTime() : 0));
        }

        player.save();
    }
}
