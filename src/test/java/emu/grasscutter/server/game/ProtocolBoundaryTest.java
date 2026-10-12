package emu.grasscutter.server.game;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.CombineReqOuterClass.CombineReq;
import emu.grasscutter.net.proto.UnionCmdNotifyOuterClass.UnionCmdNotify;
import emu.grasscutter.net.proto.UnionCmdOuterClass.UnionCmd;
import emu.grasscutter.server.packet.recv.HandlerUnionCmdNotify;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;

class ProtocolBoundaryTest {
    static int calls;
    static GameServerPacketHandler router() throws Exception {
        var router = allocate(GameServerPacketHandler.class);
        var f = GameServerPacketHandler.class.getDeclaredField("handlers"); f.setAccessible(true);
        f.set(router, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<PacketHandler>());
        return router;
    }
    @Opcodes(PacketOpcodes.CombineReq)
    public static class Combine extends PacketHandler {
        @Override public void handle(GameSession s, byte[] h, byte[] p) { calls++; }
    }
    @Opcodes(60000)
    public static class HighOpcode extends PacketHandler {
        @Override public void handle(GameSession s, byte[] h, byte[] p) { calls++; }
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return type.cast(((Unsafe) f.get(null)).allocateInstance(type));
    }
    @BeforeEach void reset() { calls = 0; }
    @Test void unknownCombineShapeCannotInvokeMutation() throws Exception {
        var r = router(); r.registerPacketHandler(Combine.class);
        var s = new GameSession(null); s.setState(GameSession.SessionState.ACTIVE);
        byte[] body = CombineReq.newBuilder().setCombineId(1).setCombineCount(1).build().toByteArray();
        r.handle(s, 65534, new byte[0], body);
        assertEquals(0, calls);
        s.setState(GameSession.SessionState.WAITING_FOR_TOKEN);
        r.handle(s, PacketOpcodes.CombineReq, new byte[0], body);
        assertEquals(0, calls);
        s.setState(GameSession.SessionState.ACTIVE);
        r.handle(s, PacketOpcodes.CombineReq, new byte[0], body);
        assertEquals(1, calls);
    }
    @Test void canceledRegisteredPacketCannotMutate() throws Exception {
        var manager = allocate(emu.grasscutter.plugin.PluginManager.class);
        var handlers = emu.grasscutter.plugin.PluginManager.class.getDeclaredField("handlers"); handlers.setAccessible(true);
        var cancel = new emu.grasscutter.server.event.EventHandler<>(emu.grasscutter.server.event.game.ReceivePacketEvent.class)
                .listener(event -> event.cancel());
        handlers.set(manager, java.util.Map.of(emu.grasscutter.server.event.game.ReceivePacketEvent.class, java.util.List.of(cancel)));
        var field = Grasscutter.class.getDeclaredField("pluginManager"); field.setAccessible(true);
        var old = field.get(null);
        try {
            field.set(null, manager);
            var r = router(); r.registerPacketHandler(Combine.class);
            var s = new GameSession(null); s.setState(GameSession.SessionState.ACTIVE);
            r.handle(s, PacketOpcodes.CombineReq, new byte[0], new byte[0]);
            assertEquals(0, calls);
        } finally { field.set(null, old); }
    }
    @Test void aliasesCannotInstallOpcodeZero() throws Exception {
        var r = router(); r.registerPacketHandler(Combine.class);
        var method = GameServerPacketHandler.class.getDeclaredMethod("registerOpcodeAliases");
        method.setAccessible(true); method.invoke(r);
        var field = GameServerPacketHandler.class.getDeclaredField("handlers"); field.setAccessible(true);
        var handlers = (it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>) field.get(r);
        assertFalse(handlers.containsKey(0));
        r.handle(new GameSession(null), 0, new byte[0], new byte[0]);
        assertEquals(0, calls);
    }
    @Test void opcodeIsUnsignedOnWire() throws Exception {
        var r = router(); r.registerPacketHandler(HighOpcode.class);
        var server = allocate(GameServer.class);
        var f = GameServer.class.getDeclaredField("packetHandler"); f.setAccessible(true); f.set(server, r);
        var session = new GameSession(server); session.setState(GameSession.SessionState.ACTIVE);
        boolean old = Grasscutter.getConfig().server.game.useXorEncryption;
        try {
            Grasscutter.getConfig().server.game.useXorEncryption = false;
            session.handleReceive(ByteBuffer.allocate(12).putShort((short)17767)
                .putShort((short)60000).putShort((short)0).putInt(0).putShort((short)-30293).array());
            assertEquals(1, calls);
        } finally { Grasscutter.getConfig().server.game.useXorEncryption = old; }
    }
    @Test void unsignedHeaderLengthCanUseHighBit() throws Exception {
        var r = router(); r.registerPacketHandler(HighOpcode.class);
        var server = allocate(GameServer.class);
        var field = GameServer.class.getDeclaredField("packetHandler"); field.setAccessible(true); field.set(server, r);
        var session = new GameSession(server); session.setState(GameSession.SessionState.ACTIVE);
        boolean old = Grasscutter.getConfig().server.game.useXorEncryption;
        try {
            Grasscutter.getConfig().server.game.useXorEncryption = false;
            var frame = ByteBuffer.allocate(12 + 32768).putShort((short)17767)
                    .putShort((short)60000).putShort((short)32768).putInt(0);
            frame.position(10 + 32768); frame.putShort((short)-30293);
            session.handleReceive(frame.array()); assertEquals(1, calls);
        } finally { Grasscutter.getConfig().server.game.useXorEncryption = old; }
    }
    @Test void oversizedUnionPayloadRejectsBeforeParsing() {
        assertThrows(IllegalArgumentException.class, () -> new HandlerUnionCmdNotify()
                .handle(null, new byte[0], new byte[1024 * 1024 + 1]));
    }
    @Test void malformedLengthsRejectWithoutAllocationErrors() {
        var logger = (ch.qos.logback.classic.Logger) Grasscutter.getLogger();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        boolean old = Grasscutter.getConfig().server.game.useXorEncryption;
        try {
            Grasscutter.getConfig().server.game.useXorEncryption = false;
            for (int length : new int[] {-1, 2 * 1024 * 1024}) {
                new GameSession(null).handleReceive(ByteBuffer.allocate(12).putShort((short)17767)
                    .putShort((short)1).putShort((short)0).putInt(length).putShort((short)-30293).array());
            }
            assertTrue(appender.list.stream().noneMatch(e -> e.getThrowableProxy() != null),
                "Length rejection must occur before allocation/read exceptions");
        } finally {
            Grasscutter.getConfig().server.game.useXorEncryption = old;
            logger.detachAppender(appender); appender.stop();
        }
    }
    @Test void ordinaryUnionAtCommandLimitDispatchesThroughRouter() throws Exception {
        var r = router(); r.registerPacketHandler(HighOpcode.class);
        var server = allocate(GameServer.class);
        var field = GameServer.class.getDeclaredField("packetHandler"); field.setAccessible(true); field.set(server, r);
        var session = new GameSession(server); session.setState(GameSession.SessionState.ACTIVE);
        var player = allocate(emu.grasscutter.game.player.Player.class);
        for (String name : new String[] {"combatInvokeHandler", "abilityInvokeHandler"}) {
            var f = emu.grasscutter.game.player.Player.class.getDeclaredField(name); f.setAccessible(true);
            f.set(player, new emu.grasscutter.game.player.InvokeHandler<>(BasePacket.class));
        }
        var attacks = emu.grasscutter.game.player.Player.class.getDeclaredField("attackResults"); attacks.setAccessible(true);
        attacks.set(player, new java.util.ArrayDeque<>());
        var playerField = GameSession.class.getDeclaredField("player"); playerField.setAccessible(true); playerField.set(session, player);
        var batch = UnionCmdNotify.newBuilder();
        for (int i = 0; i < 256; i++) batch.addCmdList(UnionCmd.newBuilder().setMessageId(60000));
        new HandlerUnionCmdNotify().handle(session, new byte[0], batch.build().toByteArray());
        assertEquals(256, calls);
        batch.addCmdList(UnionCmd.newBuilder().setMessageId(PacketOpcodes.UnionCmdNotify)); calls = 0;
        assertThrows(IllegalArgumentException.class, () -> new HandlerUnionCmdNotify().handle(session, new byte[0], batch.build().toByteArray()));
        assertEquals(0, calls, "Invalid batches must reject before their valid prefix mutates");
    }
    @Test void nestedUnionIsRejectedBeforeDispatch() {
        byte[] body = UnionCmdNotify.newBuilder().addCmdList(UnionCmd.newBuilder()
            .setMessageId(PacketOpcodes.UnionCmdNotify).setBody(ByteString.EMPTY)).build().toByteArray();
        assertThrows(IllegalArgumentException.class, () -> new HandlerUnionCmdNotify().handle(null, new byte[0], body));
    }
    @Test void oversizedCommandBatchIsRejectedBeforeDispatch() {
        var batch = UnionCmdNotify.newBuilder();
        for (int i = 0; i < 257; i++) batch.addCmdList(UnionCmd.newBuilder().setMessageId(1));
        assertThrows(IllegalArgumentException.class, () -> new HandlerUnionCmdNotify().handle(null, new byte[0], batch.build().toByteArray()));
    }
}
