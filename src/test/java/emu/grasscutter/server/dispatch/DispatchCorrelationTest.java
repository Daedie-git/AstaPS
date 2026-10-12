package emu.grasscutter.server.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.Test;
import org.slf4j.*;

class DispatchCorrelationTest {
    static class Dispatcher implements IDispatcher {
        final Map<Integer, BiConsumer<WebSocket, JsonElement>> handlers = new HashMap<>();
        final Map<Integer, List<Consumer<JsonElement>>> callbacks = new HashMap<>();
        final List<JsonObject> sent = new ArrayList<>();
        public Logger getLogger() { return LoggerFactory.getLogger(getClass()); }
        public Map<Integer, BiConsumer<WebSocket, JsonElement>> getHandlers() { return handlers; }
        public Map<Integer, List<Consumer<JsonElement>>> getCallbacks() { return callbacks; }
        public void sendMessage(int id, Object data) { sent.add(encodeMessage(id, data)); }
        public JsonObject decodeMessage(byte[] ignored) { return incoming; }
        JsonObject incoming;
        int responseId = 2;
        void reply(int index, String value) {
            var response = new JsonObject();
            response.addProperty("value", value);
            incoming = encodeMessage(responseId, response);
            if (sent.get(index).has("requestKey"))
                incoming.add("requestKey", sent.get(index).get("requestKey"));
            deliver();
        }
        void deliver() {
            var socket = (WebSocket) java.lang.reflect.Proxy.newProxyInstance(
                    WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
                    (proxy, method, args) -> method.getName().equals("getAttachment") ? Boolean.TRUE : null);
            handleMessage(socket, new byte[0]);
        }
    }
    @Test void handlerEchoesRequestKeyOnResponseEnvelope() {
        var d = new Dispatcher();
        d.registerHandler(1, (socket, packet) -> d.sendMessage(2, IDispatcher.decode(packet)));
        var body = new JsonObject();
        body.addProperty("value", "own request");
        var request = d.async(body, 1, 2);
        d.incoming = d.sent.get(0);
        d.deliver();
        assertEquals(d.sent.get(0).get("requestKey"), d.sent.get(1).get("requestKey"));
        d.incoming = d.sent.get(1);
        d.deliver();
        assertEquals("own request", request.join().get("value").getAsString());
        assertNull(IDispatcher.REQUEST_KEY.get());
    }
    @Test void cancelledRequestsRemoveCallbacksAndIgnoreLateReplies() {
        var d = new Dispatcher();
        var request = d.async(new JsonObject(), 1, 2);
        request.cancel(true);
        assertTrue(d.pendingRequests().isEmpty());
        d.reply(0, "late");
        assertTrue(request.isCancelled());
    }
    @Test void wrongResponseTypeAndUncorrelatedRepliesDoNotCompleteRequest() {
        var d = new Dispatcher();
        var request = d.async(new JsonObject(), 1, 2);
        d.responseId = 3;
        d.reply(0, "wrong type");
        assertFalse(request.isDone());
        d.responseId = 2;
        var key = d.sent.get(0).remove("requestKey");
        d.reply(0, "uncorrelated");
        assertFalse(request.isDone());
        d.sent.get(0).add("requestKey", key);
        d.reply(0, "correct");
        assertEquals("correct", request.join().get("value").getAsString());
        assertTrue(d.pendingRequests().isEmpty());
    }
    @Test void parserFailureCompletesExceptionallyAndRemovesRequest() {
        var d = new Dispatcher();
        var request = d.async(new JsonObject(), 1, 2, packet -> { throw new IllegalArgumentException(); });
        d.reply(0, "bad");
        assertTrue(request.isCompletedExceptionally());
        assertTrue(d.pendingRequests().isEmpty());
    }
    @Test void interruptedAwaitCancelsRequestAndPreservesInterrupt() {
        var d = new Dispatcher();
        try {
            Thread.currentThread().interrupt();
            assertNull(d.await(new JsonObject(), 1, 2, IDispatcher.DEFAULT_PARSER));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(d.pendingRequests().isEmpty());
        } finally { Thread.interrupted(); }
    }
    @Test void overlappingRequestsReceiveOnlyTheirOwnReply() {
        var d = new Dispatcher();
        var first = d.async(new JsonObject(), 1, 2);
        var second = d.async(new JsonObject(), 1, 2);
        d.reply(1, "second");
        assertFalse(first.isDone(), "another request's reply must not complete the first request");
        assertEquals("second", second.join().get("value").getAsString());
        d.reply(0, "first");
        assertEquals("first", first.join().get("value").getAsString());
    }
}
