package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.server.game.GameServer;
import org.junit.jupiter.api.Test;

class PlayerLogoutTest {
    private static class FailingCleanupPlayer extends Player {
        int attempts, removals;
        @Override public GameServer getServer() {
            attempts++;
            throw new IllegalStateException("isolated cleanup failure");
        }
        @Override public void removeFromServer() { removals++; }
    }
    @Test void logoutAndCloseCannotRepeatCleanupEvenAfterFailure() {
        var player = new FailingCleanupPlayer();
        var failure = assertThrows(IllegalStateException.class, player::onLogout);
        assertEquals("isolated cleanup failure", failure.getCause().getMessage());
        assertDoesNotThrow(player::onLogout);
        assertEquals(1, player.attempts);
        assertEquals(1, player.removals);
    }
}
