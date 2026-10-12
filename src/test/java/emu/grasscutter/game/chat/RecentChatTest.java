package emu.grasscutter.game.chat;

import static emu.grasscutter.audit.MultiplayerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.config.Configuration.GAME_INFO;
import emu.grasscutter.GameConstants;
import emu.grasscutter.net.proto.PullRecentChatRspOuterClass.PullRecentChatRsp;
import emu.grasscutter.net.proto.ChatInfoOuterClass.ChatInfo;
import emu.grasscutter.server.packet.send.PacketPullRecentChatRsp;
import java.util.*;
import org.junit.jupiter.api.Test;

final class RecentChatTest {
    @Test void humanConversationsAreIncludedNewestFirstAndCallerIsIsolated() throws Exception {
        var s = server(); var a = player(s, 100, null, 3); var b = player(s, 200, null, 3);
        var chat = new ChatSystem(s);
        Map<Integer, Map<Integer, List<ChatInfo>>> history = new HashMap<>();
        history.put(100, Map.of(200, List.of(info(100, 200, 10), info(200, 100, 20)),
            300, List.of(info(300, 100, 30)), GameConstants.SERVER_CONSOLE_UID, List.of(info(GameConstants.SERVER_CONSOLE_UID, 100, 5))));
        history.put(200, new HashMap<>(Map.of(400, List.of(info(400, 200, 99)))));
        field(chat, ChatSystem.class, "history", history);
        chat.handlePullRecentChatReq(a);
        assertEquals(List.of(30, 20, 10, 5), response(a).getChatInfoList().stream().map(ChatInfo::getTime).toList());
        chat.handlePullRecentChatReq(b);
        assertTrue(response(b).getChatInfoList().stream().noneMatch(i -> i.getToUid() == 100));
    }
    @Test void recentHistoryIterationUsesTheSameLockAsHistoryMutation() throws Exception {
        var s = server(); var p = player(s, 100, null, 3);
        var chat = new ChatSystem(s);
        Map<Integer, Map<Integer, List<ChatInfo>>> history = new HashMap<>();
        Map<Integer, List<ChatInfo>> conversations = new HashMap<>() {
            @Override public Collection<List<ChatInfo>> values() {
                assertTrue(Thread.holdsLock(history), "history iteration must be serialized with writers");
                return super.values();
            }
        };
        conversations.put(GameConstants.SERVER_CONSOLE_UID, List.of(info(1, 100, 5)));
        history.put(100, conversations);
        field(chat, ChatSystem.class, "history", history);
        chat.handlePullRecentChatReq(p);
        assertEquals(1, response(p).getChatInfoCount());
    }
    @Test void loginConversationUsesLockedSnapshotsForBothPackets() throws Exception {
        var chat = new ChatSystem(server());
        Map<Integer, Map<Integer, List<ChatInfo>>> history = new HashMap<>() {
            @Override public Map<Integer, List<ChatInfo>> computeIfAbsent(Integer key,
                    java.util.function.Function<? super Integer, ? extends Map<Integer, List<ChatInfo>>> factory) {
                assertTrue(Thread.holdsLock(this), "login must use the history mutation lock");
                return super.computeIfAbsent(key, factory);
            }
        };
        var messages = new ArrayList<>(List.of(info(GameConstants.SERVER_CONSOLE_UID, 100, 5)));
        history.put(100, new HashMap<>(Map.of(GameConstants.SERVER_CONSOLE_UID, messages)));
        field(chat, ChatSystem.class, "history", history);
        var p = allocate(LoginPlayer.class); p.uid = 100; p.sent = new ArrayList<>();
        p.historyLock = history; p.messages = messages;
        chat.ensureServerConversation(p);
        assertEquals(1, response(p).getChatInfoCount());
        var privatePacket = p.sent.stream().filter(emu.grasscutter.server.packet.send.PacketPullPrivateChatRsp.class::isInstance).findFirst().orElseThrow();
        var privateReply = emu.grasscutter.net.proto.PullPrivateChatRspOuterClass.PullPrivateChatRsp.parseFrom(privatePacket.getData());
        assertEquals(1, privateReply.getChatInfoCount(), "a reentrant write between sends must not change the captured login conversation");
    }
    static class LoginPlayer extends P {
        Object historyLock; List<ChatInfo> messages;
        @Override public void sendPacket(emu.grasscutter.net.packet.BasePacket packet) {
            assertFalse(Thread.holdsLock(historyLock)); sent.add(packet);
            if (sent.size() == 1) synchronized (historyLock) { messages.add(info(1, 100, 10)); }
        }
    }
    @Test void emptyHistoryAlwaysReceivesAnEmptyResponse() throws Exception {
        String oldMessage = GAME_INFO.joinOptions.welcomeMessage;
        int[] oldEmotes = GAME_INFO.joinOptions.welcomeEmotes;
        try {
            GAME_INFO.joinOptions.welcomeMessage = ""; GAME_INFO.joinOptions.welcomeEmotes = new int[0];
            var s = server(); var p = player(s, 100, null, 3);
            new ChatSystem(s).handlePullRecentChatReq(p);
            assertEquals(0, response(p).getChatInfoCount());
        } finally { GAME_INFO.joinOptions.welcomeMessage = oldMessage; GAME_INFO.joinOptions.welcomeEmotes = oldEmotes; }
    }
    static ChatInfo info(int from, int to, int time) { return ChatInfo.newBuilder().setUid(from).setToUid(to).setTime(time).setText("hello").build(); }
    static PullRecentChatRsp response(P p) throws Exception {
        var replies = p.sent.stream().filter(PacketPullRecentChatRsp.class::isInstance).toList();
        assertEquals(1, replies.size(), "one recent response per request, including empty history");
        return PullRecentChatRsp.parseFrom(replies.get(0).getData());
    }
}
