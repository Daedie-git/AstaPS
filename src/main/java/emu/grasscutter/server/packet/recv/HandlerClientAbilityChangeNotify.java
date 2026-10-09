package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.game.ability.AmberSupport;
import emu.grasscutter.net.proto.ClientAbilityChangeNotifyOuterClass.ClientAbilityChangeNotify;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.ClientAbilityChangeNotify)
public final class HandlerClientAbilityChangeNotify extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var notif = ClientAbilityChangeNotify.parseFrom(payload);

        var player = session.getPlayer();
        for (var original : notif.getInvokesList()) {
            var entry = AmberSupport.tuneInvoke(player, original);
            player.getAbilityManager().onAbilityInvoke(entry);
            player.getAbilityInvokeHandler().addEntry(entry.getForwardType(), entry);
        }
        player.getAbilityManager().flushPendingBoL();
    }
}
