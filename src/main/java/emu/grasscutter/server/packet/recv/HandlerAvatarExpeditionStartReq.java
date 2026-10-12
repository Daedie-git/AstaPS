package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.AvatarExpeditionStartReqOuterClass.AvatarExpeditionStartReq;
import emu.grasscutter.net.proto.PacketHeadOuterClass.PacketHead;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketAvatarExpeditionAllDataRsp;
import emu.grasscutter.server.packet.send.PacketAvatarExpeditionDataNotify;
import emu.grasscutter.server.packet.send.PacketAvatarExpeditionStartRsp;
import emu.grasscutter.utils.Utils;

@Opcodes(PacketOpcodes.AvatarExpeditionStartReq)
public class HandlerAvatarExpeditionStartReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        AvatarExpeditionStartReq req = AvatarExpeditionStartReq.parseFrom(payload);
        var player = session.getPlayer();

        int clientSeq = 0;
        try {
            if (header != null && header.length > 0) {
                clientSeq = PacketHead.parseFrom(header).getClientSequenceId();
            }
        } catch (Exception ignored) {
        }

        // 7.0 sends a list of expeditions in one request instead of a single flattened one.
        int startTime = Utils.getCurrentSeconds();
        var assignments = req.getBasicInfoListList().stream()
                .map(info -> new emu.grasscutter.game.expedition.ExpeditionHelper.Assignment(
                        info.getAvatarGuid(), info.getExpId(), info.getHourTime())).toList();
        boolean started = emu.grasscutter.game.expedition.ExpeditionHelper.startAll(player, assignments, startTime);

        // StartRsp must include _expedition_basic_info_list or the open UI won't refresh.
        var startRsp =
                new PacketAvatarExpeditionStartRsp(
                        player.getExpeditionInfo(), started ? req.getBasicInfoListList() : java.util.List.of());
        if (clientSeq > 0) {
            startRsp.buildHeader(clientSeq);
        }
        session.send(startRsp);
        session.send(new PacketAvatarExpeditionDataNotify(player.getExpeditionInfo()));
        session.send(
                new PacketAvatarExpeditionAllDataRsp(
                        player.getExpeditionInfo(), player.getExpeditionLimit()));
    }
}
