package emu.grasscutter.server.packet.recv;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GetHomeLevelUpRewardReqOuterClass.GetHomeLevelUpRewardReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketGetHomeLevelUpRewardRsp;
import java.util.*;

@Opcodes(PacketOpcodes.GetHomeLevelUpRewardReq)
public class HandlerGetHomeLevelUpRewardReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        // Replica of TakePlayerLevelReward for adv rank rewards
        Player pl = session.getPlayer();
        synchronized (pl) {
            GetHomeLevelUpRewardReq req = GetHomeLevelUpRewardReq.parseFrom(payload);
            int level = req.getLevel();
            var row = GameData.getHomeWorldLevelDataMap().get(level);
            if (level <= 0 || pl.getHome() == null || pl.getRealmList() == null
                    || pl.getRealmList().isEmpty() || level > pl.getHome().getLevel()
                    || row == null || row.getRewardId() <= 0
                    || GameData.getRewardDataMap().get(row.getRewardId()) == null) {
                session.send(new PacketGetHomeLevelUpRewardRsp(level, 0));
                return;
            }
            Set<Integer> homeRewardedLevels = session.getPlayer().getHomeRewardedLevels();
            if (homeRewardedLevels == null) homeRewardedLevels = new HashSet<>();
            if (!homeRewardedLevels.contains(level)) { // No duplicated reward
                int rewardId = GameData.getHomeWorldLevelDataMap().get(level).getRewardId();
                if (rewardId != 0) {
                    List<ItemParamData> rewardItems =
                            GameData.getRewardDataMap().get(rewardId).getRewardItemList();
                    var claim = emu.grasscutter.game.inventory.ClaimBoundary.reserve(pl, "home-rank:" + level);
                    if (claim == null) return;
                    if (rewardItems == null || rewardItems.isEmpty()
                            || !emu.grasscutter.game.inventory.ClaimRewardDelivery.tryGrantParams(pl.getInventory(), rewardItems, ActionReason.GetHomeLevelupReward)) {
                        claim.release();
                        session.send(new PacketGetHomeLevelUpRewardRsp(level, 0));
                        return;
                    }
                    homeRewardedLevels.add(level);
                    pl.setHomeRewardedLevels(homeRewardedLevels);
                    claim.release();
                    pl.save();
                    pl.getHome().onClaimReward(pl);
                    session.send(new PacketGetHomeLevelUpRewardRsp(level, rewardId));
                }
            }
        }
    }
}
