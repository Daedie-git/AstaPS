import com.google.protobuf.CodedInputStream;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.ItemParamOuterClass.ItemParam;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.net.proto.TakePlayerLevelRewardReqOuterClass.TakePlayerLevelRewardReq;
import emu.grasscutter.server.packet.send.PacketTakeAchievementGoalRewardRsp;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Captured rank decoding and native goal-response bytes; no session, rewards or database. */
public final class CheckOfflineRewardProtocol {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rank() throws Exception {
        for (int level : List.of(2, 3, 4)) {
            byte[] captured = {(byte) 0x78, (byte) level};
            int actual = TakePlayerLevelRewardReq.parseFrom(captured).getLevel();
            check(actual == level, "Captured rank " + level + " decoded as " + actual);
            check(Arrays.equals(captured, TakePlayerLevelRewardReq.newBuilder().setLevel(level).build().toByteArray()),
                    "Generated rank request must emit native field 15");
        }
        check(TakePlayerLevelRewardReq.getDescriptor().findFieldByName("level").getNumber() == 15,
                "Canonical rank descriptor must match native/captured field 15");
        System.out.println("PASS: generated rank schema decodes and encodes captured fields.");
    }

    private static void goal() throws Exception {
        var item = ItemParam.newBuilder().setItemId(201).setCount(5).build();
        var packet = new PacketTakeAchievementGoalRewardRsp(List.of(1), List.of(item));
        check(packet.getOpcode() == 22054, "Goal reward response opcode is " + packet.getOpcode());
        check(Arrays.equals(packet.getData(), HexFormat.of().parseHex("4201014a0508c9011005")),
                "Goal response must encode IDs on field 8 and items on field 9: "
                        + HexFormat.of().formatHex(packet.getData()));
        var error = new PacketTakeAchievementGoalRewardRsp();
        var input = CodedInputStream.newInstance(error.getData());
        check(error.getOpcode() == 22054 && input.readTag() == 56,
                "Goal error retcode must use native field 7");
        check(input.readInt32() == Retcode.RET_REWARD_HAS_TAKEN_VALUE && input.isAtEnd(),
                "Goal response must retain existing error semantics");
        check(PacketOpcodes.TakeAchievementGoalRewardReq == 0,
                "Unverified goal request opcode must remain unmapped");
        System.out.println("PASS: real goal-response packet uses verified opcode and success/error bytes.");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("rank")) rank();
        if (args.length == 0 || args[0].equals("goal")) goal();
    }
}
