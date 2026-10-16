package emu.grasscutter.game.achievement;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.recv.HandlerTakeAchievementRewardReq;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import sun.misc.Unsafe;

/** Captured requests through the actual handler; no database, rewards or player mutations. */
public final class CheckAchievementClaimProtocol {
    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class RecordingAchievements extends Achievements {
        List<Integer> claimed;
        boolean eligible = true;
        RecordingAchievements() { super(null, 1, null, new HashMap<>(), 0, new ArrayList<>()); }
        @Override public boolean isInvalid(int id) { return id != 82041; }
        @Override public boolean isRewardLeft(int id) { return eligible; }
        @Override public synchronized void takeReward(List<Integer> ids) { claimed = List.copyOf(ids); }
    }
    private static final class RecordingPlayer extends Player {
        RecordingAchievements achievements;
        @Override public Achievements getAchievements() { return achievements; }
    }
    private static final class RecordingSession extends GameSession {
        Player player;
        final List<BasePacket> sent = new ArrayList<>();
        RecordingSession() { super(null); setState(SessionState.ACTIVE); }
        @Override public Player getPlayer() { return player; }
        @Override public void send(BasePacket packet) { sent.add(packet); }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Class.forName("emu.grasscutter.Grasscutter");
        var handler = new HandlerTakeAchievementRewardReq();
        var player = allocate(RecordingPlayer.class);
        player.achievements = new RecordingAchievements();
        var session = new RecordingSession();
        session.player = player;
        // Live 2026-10-12 opcode 25857, packed field 15, achievement 82041.
        handler.handle(session, new byte[0], HexFormat.of().parseHex("7a03f98005"));
        check(List.of(82041).equals(player.achievements.claimed), "Captured claim must decode achievement 82041, got " + player.achievements.claimed);
        handler.handle(session, new byte[0], HexFormat.of().parseHex("08017a03f98005"));
        check(List.of(82041).equals(player.achievements.claimed), "Unknown fields must not hide a claim");
        // Protobuf permits unpacked repeated uint32 values as well as packed values.
        handler.handle(session, new byte[0], HexFormat.of().parseHex("78f98005"));
        check(List.of(82041).equals(player.achievements.claimed), "Unpacked achievement IDs");
        for (String hex : List.of("", "5203f98005", "7a03f980", "7df9800500", "7c", "7a0100", "7a06f98005f98005")) {
            player.achievements.claimed = null;
            session.sent.clear();
            handler.handle(session, new byte[0], HexFormat.of().parseHex(hex));
            check(player.achievements.claimed == null, "Invalid/duplicate claim reached rewards: " + hex);
            check(session.sent.size() == 1, "Invalid claim must be answered: " + hex);
        }
        player.achievements.eligible = false;
        player.achievements.claimed = null;
        session.sent.clear();
        handler.handle(session, new byte[0], HexFormat.of().parseHex("7a03f98005"));
        check(player.achievements.claimed == null && session.sent.size() == 1, "Unearned or already-taken claim must not reach delivery");
        player.achievements.eligible = true;
        session.player = null;
        session.sent.clear();
        handler.handle(session, new byte[0], HexFormat.of().parseHex("7a03f98005"));
        check(session.sent.size() == 1, "Missing player must be rejected");
        check(PacketOpcodes.TakeAchievementRewardReq == 25857, "Captured achievement opcode must be registered");
        session.player = player;
        player.achievements.claimed = null;
        var router = new emu.grasscutter.server.game.GameServerPacketHandler(emu.grasscutter.net.packet.PacketHandler.class);
        router.handle(session, 25857, new byte[0], HexFormat.of().parseHex("7a03f98005"));
        check(List.of(82041).equals(player.achievements.claimed), "Real dispatcher must route the captured opcode to the fixed handler");
        var item = emu.grasscutter.net.proto.ItemParamOuterClass.ItemParam.newBuilder().setItemId(201).setCount(5).build();
        var success = new emu.grasscutter.server.packet.send.PacketTakeAchievementRewardRsp(List.of(82041), List.of(item));
        check(success.getOpcode() == 9071, "Success response must use the client-verified command ID");
        check(java.util.Arrays.equals(success.getData(), HexFormat.of().parseHex("4203f980055a0508c9011005")), "Response must use client fields 8/11 and plain ItemParam fields 1/2");
        var error = new emu.grasscutter.server.packet.send.PacketTakeAchievementRewardRsp();
        var errorInput = com.google.protobuf.CodedInputStream.newInstance(error.getData());
        check(error.getOpcode() == 9071 && errorInput.readTag() == 8, "Error retcode must be on client field 1");
        check(errorInput.readInt32() == emu.grasscutter.net.proto.RetcodeOuterClass.Retcode.RET_REWARD_HAS_TAKEN_VALUE && errorInput.isAtEnd(), "Error response must retain the existing nonzero claim retcode without success fields");
        System.out.println("PASS: captured request routing, strict decoding, eligibility guards and client-verified success/error response bytes; no rewards or database writes.");
    }
}
