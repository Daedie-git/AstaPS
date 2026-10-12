package emu.grasscutter.server.game;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.player.Player;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ShutdownLogoutTest {
    @Test void explicitLogoutAcknowledgesCleanupBeforeDelayedTransportCallback() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var player = allocate(RecordingPlayer.class);
        var session = new RecordingSession(player);
        try {
            executor.execute(() -> { entered.countDown(); await(release); });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var logout = GameSessionManager.logoutSessions(executor, List.of(session));
            assertFalse(logout.isDone());
            release.countDown();
            logout.get(2, TimeUnit.SECONDS);
            assertEquals(1, player.saves);
            executor.submit(session::handleClose).get(2, TimeUnit.SECONDS);
            assertEquals(1, player.saves, "late KCP callback must not repeat logout");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void duplicateConcurrentCloseRunsPlayerLogoutOnce() throws Exception {
        var player = allocate(RecordingPlayer.class);
        var session = new RecordingSession(player);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = callers.submit(session::handleClose);
            var b = callers.submit(session::handleClose);
            a.get(2, TimeUnit.SECONDS); b.get(2, TimeUnit.SECONDS);
            assertEquals(1, player.saves);
        }
    }

    @Test void failedLogoutStillNotifiesAndClearsTunnelWhileRetirementFails() throws Exception {
        var player = allocate(FailingPlayer.class);
        var session = new RecordingSession(player);
        var field = GameSession.class.getDeclaredField("tunnel");
        field.setAccessible(true);
        field.set(session, new GameSessionManager.KcpTunnel() {
            public InetSocketAddress getAddress() { return session.getAddress(); }
            public void writeData(byte[] bytes) {}
            public void close() {}
            public int getSrtt() { return 0; }
        });
        assertDoesNotThrow(session::handleClose);
        assertEquals(1, session.sends);
        assertFalse(session.isConnected());
        assertThrows(CompletionException.class, () -> session.retireAccountLease().join());
        assertDoesNotThrow(session::handleClose);
        assertEquals(1, player.saves);
        assertEquals(1, session.sends);
    }

    static class FailingPlayer extends RecordingPlayer {
        @Override public void onLogout() { saves++; throw new IllegalStateException("save rejected"); }
    }
    static class RecordingPlayer extends Player {
        int saves;
        @Override public void onLogout() { saves++; }
    }
    static class RecordingSession extends GameSession {
        final RecordingPlayer player;
        int sends;
        RecordingSession(RecordingPlayer player) { super(null); this.player = player; markComponentsReady(); }
        @Override public Player getPlayer() { return player; }
        @Override public InetSocketAddress getAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        @Override public void close() { /* Transport callback is deliberately delayed. */ }
        @Override public void send(emu.grasscutter.net.packet.BasePacket packet) { sends++; }
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
