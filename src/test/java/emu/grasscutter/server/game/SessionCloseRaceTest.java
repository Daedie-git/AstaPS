package emu.grasscutter.server.game;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.game.player.Player;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionCloseRaceTest {
    static class Tunnel implements GameSessionManager.KcpTunnel {
        public InetSocketAddress getAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        public void writeData(byte[] bytes) {}
        public void close() {} // Transport callback deliberately delayed.
        public int getSrtt() { return 0; }
    }
    static class Logout extends Player {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final boolean fail;
        final AtomicBoolean finalWriteAdmitted = new AtomicBoolean();
        Logout(boolean fail) { this.fail = fail; }
        @Override public void onLogout() {
            assertFalse(Thread.holdsLock(getSession()), "logout must not execute under the session admission monitor");
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            finalWriteAdmitted.set(getSession().canSubmitSessionWrite());
            if (fail) throw new IllegalStateException("late logout failure");
        }
    }
    @Test void concurrentTransportCloseWaitsForFinalLogoutAndAcknowledgement() throws Exception { race(false); }
    @Test void lateLogoutFailureNeverReleasesOwnership() throws Exception { race(true); }
    private void race(boolean fail) throws Exception {
        String account = "logout-race-" + fail;
        var session = new GameSession(null);
        var player = new Logout(fail);
        var ack = new CompletableFuture<Void>();
        var pool = Executors.newSingleThreadExecutor();
        LoginPersistenceHandoff.install(() -> ack);
        try {
            session.onConnected(new Tunnel());
            assertTrue(session.claimAccountLease(account));
            session.setPlayer(player);
            session.markComponentsReady();
            session.markLeaseLoaded();
            var close = pool.submit(session::close);
            assertTrue(player.entered.await(5, TimeUnit.SECONDS));
            session.handleClose();
            var retirement = session.retireAccountLease();
            assertFalse(retirement.isDone());
            ack.complete(null);
            assertTrue(session.ownsAccountLease());
            player.release.countDown();
            close.get(5, TimeUnit.SECONDS);
            assertTrue(player.finalWriteAdmitted.get());
            if (fail) {
                assertThrows(CompletionException.class, retirement::join);
                assertTrue(session.ownsAccountLease());
            } else {
                retirement.get(5, TimeUnit.SECONDS);
                assertFalse(session.ownsAccountLease());
            }
        } finally {
            player.release.countDown(); pool.shutdownNow();
            AccountSessionLease.release(AccountSessionLease.current(account));
            LoginPersistenceHandoff.install(() -> CompletableFuture.failedFuture(new IllegalStateException("not installed")));
        }
    }
    @Test void closeJoinsLoaderBeforeStartingOnceOnlyLogout() throws Exception {
        String account = "close-loader-logout";
        var session = new GameSession(null); var player = new Logout(false);
        var pool = Executors.newSingleThreadExecutor();
        LoginPersistenceHandoff.install(() -> CompletableFuture.completedFuture(null));
        try {
            session.onConnected(new Tunnel()); assertTrue(session.claimAccountLease(account));
            session.setPlayer(player); session.markLeaseLoaded(); assertTrue(session.beginLoginWork());
            session.close(); session.handleClose();
            var retirement = session.retireAccountLease();
            assertEquals(1, player.entered.getCount()); assertFalse(retirement.isDone());
            session.markComponentsReady();
            var loader = pool.submit(session::endLoginWork);
            assertTrue(player.entered.await(5, TimeUnit.SECONDS));
            assertFalse(retirement.isDone()); assertTrue(session.ownsAccountLease());
            player.release.countDown(); loader.get(5, TimeUnit.SECONDS); retirement.get(5, TimeUnit.SECONDS);
            assertTrue(player.finalWriteAdmitted.get()); assertFalse(session.ownsAccountLease());
        } finally {
            player.release.countDown(); pool.shutdownNow();
            AccountSessionLease.release(AccountSessionLease.current(account));
            LoginPersistenceHandoff.install(() -> CompletableFuture.failedFuture(new IllegalStateException("not installed")));
        }
    }
    @Test void queuedTokenContinuationCannotReviveClosedSession() throws Exception {
        var session = new GameSession(null);
        session.onConnected(new Tunnel());
        var loginClass = Class.forName("emu.grasscutter.server.packet.recv.HandlerGetPlayerTokenReq$Login");
        var ctor = loginClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        var login = ctor.newInstance(session, false, "queued-account", "", 0);
        var guarded = loginClass.getDeclaredMethod("guarded", Runnable.class);
        guarded.setAccessible(true);
        var ran = new AtomicBoolean();
        Runnable queued = () -> {
            try { guarded.invoke(login, (Runnable) () -> { ran.set(true); session.setState(GameSession.SessionState.WAITING_FOR_LOGIN); }); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        };
        session.close();
        assertTrue(session.isConnected());
        queued.run();
        assertFalse(ran.get());
        assertEquals(GameSession.SessionState.INACTIVE, session.getState());
    }
}
