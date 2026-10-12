package emu.grasscutter.server.game;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.player.Player;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SessionCloseFailureTest {
    private static final class BrokenLogoutPlayer extends Player {
        @Override public void onLogout() { throw new IllegalStateException("isolated logout failure"); }
    }
    private static final class Tunnel implements GameSessionManager.KcpTunnel {
        boolean closed;
        public InetSocketAddress getAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        public void writeData(byte[] bytes) {}
        public void close() { closed = true; }
        public int getSrtt() { return 0; }
    }
    @Test void failedLogoutStillClosesTransportAndQuarantinesAccount() {
        String account = "isolated-close-failure";
        var session = new GameSession(null);
        var tunnel = new Tunnel();
        LoginPersistenceHandoff.install(() -> CompletableFuture.completedFuture(null));
        try {
            session.onConnected(tunnel);
            assertTrue(session.claimAccountLease(account));
            session.setPlayer(new BrokenLogoutPlayer());
            session.markLeaseLoaded();
            session.markComponentsReady();
            assertDoesNotThrow(session::close);
            assertTrue(tunnel.closed);
            assertThrows(CompletionException.class, () -> session.retireAccountLease().join());
            assertFalse(new GameSession(null).claimAccountLease(account));
        } finally {
            AccountSessionLease.release(AccountSessionLease.current(account));
            LoginPersistenceHandoff.install(() -> CompletableFuture.failedFuture(new IllegalStateException("not installed")));
        }
    }
}
