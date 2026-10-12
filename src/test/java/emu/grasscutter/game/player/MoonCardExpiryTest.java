package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.*;

class MoonCardExpiryTest {
    @Test
    void expiredEntitlementStopsBeforeGrant() throws Exception {
        Field u = Unsafe.class.getDeclaredField("theUnsafe");
        u.setAccessible(true);
        Player player = (Player) ((Unsafe) u.get(null)).allocateInstance(Player.class);
        Calendar start = Calendar.getInstance();
        start.add(Calendar.DATE, -31);
        set(player, "moonCard", true);
        set(player, "moonCardStartTime", start.getTime());
        set(player, "moonCardDuration", 30);
        set(player, "moonCardGetTimes", new HashSet<Date>());
        assertDoesNotThrow(player::getTodayMoonCard);
        assertFalse(player.inMoonCard());
    }

    private static void set(Player p, String name, Object value) throws Exception {
        Field f = Player.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(p, value);
    }
}
