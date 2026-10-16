import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.game.GameSessionManager;
import emu.grasscutter.server.packet.send.PacketChooseCurAvatarTeamRsp;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** Exercise the real outgoing-packet filter with an isolated in-memory transport. */
public final class CheckTeamSwitchProtocol {
    private static final class RecordingTransport implements GameSessionManager.KcpTunnel {
        final List<byte[]> writes = new ArrayList<>();
        @Override public InetSocketAddress getAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        @Override public void writeData(byte[] data) { writes.add(data); }
        @Override public void close() {}
        @Override public int getSrtt() { return 0; }
    }
    public static void main(String[] args) throws Exception {
        Class.forName("emu.grasscutter.Grasscutter");
        var session = new GameSession(null);
        var transport = new RecordingTransport();
        var field = GameSession.class.getDeclaredField("tunnel");
        field.setAccessible(true);
        field.set(session, transport);
        BasePacket acknowledgement = new PacketChooseCurAvatarTeamRsp(2);
        // No live socket or encryption is involved; exercise only the real delivery path.
        acknowledgement.shouldEncrypt = false;
        session.send(acknowledgement);
        if (transport.writes.size() != 1)
            throw new AssertionError("First team deployment acknowledgement was discarded (opcode " + acknowledgement.getOpcode() + ")");
        if (acknowledgement.getOpcode() != 29488)
            throw new AssertionError("Team response must use the client-verified opcode 29488");
        if (!java.util.Arrays.equals(acknowledgement.getData(), new byte[] {0x28, 0x02}))
            throw new AssertionError("Team 2 success must use plain field 5: 28 02");
        var error = emu.grasscutter.net.proto.ChooseCurAvatarTeamRspOuterClass.ChooseCurAvatarTeamRsp.newBuilder().setRetcode(-1).build();
        if (!java.util.Arrays.equals(error.toByteArray(), java.util.HexFormat.of().parseHex("60ffffffffffffffffff01")))
            throw new AssertionError("Retcode must use client field 12 and signed int32, not ZigZag");
        System.out.println("PASS: first team-switch acknowledgement reaches the actual transport with client-verified opcode and wire bytes; no client, scene or player data changed.");
    }
}
