package emu.grasscutter.server.packet.send;

import emu.grasscutter.data.GameData;
import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.mail.Mail;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.EquipParamOuterClass;
import emu.grasscutter.net.proto.GetMailItemRspOuterClass.GetMailItemRsp;
import java.util.*;
import java.util.stream.Collectors;

public class PacketGetMailItemRsp extends BasePacket {

    public PacketGetMailItemRsp(Player player, List<Integer> mailList) {
        super(PacketOpcodes.GetMailItemRsp);
        List<Mail> claimedMessages = new ArrayList<>();
        List<EquipParamOuterClass.EquipParam> claimedItems = new ArrayList<>();

        GetMailItemRsp.Builder proto = GetMailItemRsp.newBuilder();

        synchronized (player) {
            boolean modified = false;
            for (int mailId : mailList) {
                Mail message = player.getMail(mailId);
                if (message != null && !message.isAttachmentGot) {
                    if (message.itemList == null) message.itemList = new ArrayList<>();
                    List<GameItem> grants = new ArrayList<>();
                    List<EquipParamOuterClass.EquipParam> responseItems = new ArrayList<>();
                    for (Mail.MailItem mailItem : message.itemList) {
                        var itemData = GameData.getItemDataMap().get(mailItem.itemId);
                        // An attachment that cannot exist is skipped; the valid ones are still delivered.
                        if (itemData == null || mailItem.itemCount <= 0) continue;
                        EquipParamOuterClass.EquipParam.Builder item =
                                EquipParamOuterClass.EquipParam.newBuilder();
                        int promoteLevel = GameItem.getMinPromoteLevel(mailItem.itemLevel);

                        item.setItemId(mailItem.itemId);
                        item.setItemNum(mailItem.itemCount);
                        item.setItemLevel(mailItem.itemLevel);
                        item.setPromoteLevel(promoteLevel);
                        responseItems.add(item.build());
                        GameItem gameItem = new GameItem(itemData);
                        gameItem.setCount(mailItem.itemCount);
                        gameItem.setLevel(mailItem.itemLevel);
                        gameItem.setPromoteLevel(promoteLevel);
                        grants.add(gameItem);
                    }
                    var claim = emu.grasscutter.game.inventory.ClaimBoundary.reserve(player, "mail:" + mailId);
                    if (claim == null) continue;
                    // Nothing deliverable: mark as got so it is not retried forever.
                    if (!grants.isEmpty()) {
                        if (!emu.grasscutter.game.inventory.ClaimRewardDelivery.tryGrant(player.getInventory(), grants, ActionReason.MailAttachment)) { claim.release(); continue; }
                        claimedItems.addAll(responseItems);
                    }

                    message.isAttachmentGot = true;
                    claim.release();
                    claimedMessages.add(message);

                    player.replaceMailByIndex(mailId, message);
                    modified = true;
                }
            }
            if (modified) {
                player.save();
            }
        }

        proto.addAllMailIdList(claimedMessages.stream().map(player::getMailId).map(player.getMailHandler() ::toClientMailId).collect(Collectors.toList()));
        proto.addAllItemList(claimedItems);

        this.setData(proto.build());
        player
                .getSession()
                .send(
                        new PacketMailChangeNotify(
                                player,
                                claimedMessages)); // For some reason you have to also send the MailChangeNotify
        // packet
    }
}
