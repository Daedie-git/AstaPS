package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.game.friends.Friendship;
import emu.grasscutter.game.player.Player;
import org.junit.jupiter.api.Test;

class RetiredPlayerWriteTest {
    @Test
    void writesFromALoggedOutPlayerObjectAreRefused() throws Exception {
        var player = new Player();
        var friendship = new Friendship();
        var owner = Friendship.class.getDeclaredField("owner");
        owner.setAccessible(true);
        owner.set(friendship, player);

        assertFalse(DatabaseHelper.fromRetiredPlayer(player));
        assertFalse(DatabaseHelper.fromRetiredPlayer(friendship));

        var retired = Player.class.getDeclaredField("retired");
        retired.setAccessible(true);
        retired.setBoolean(player, true);

        assertTrue(DatabaseHelper.fromRetiredPlayer(player));
        assertTrue(DatabaseHelper.fromRetiredPlayer(friendship));
        assertFalse(DatabaseHelper.fromRetiredPlayer(new Object()));

    }
}
