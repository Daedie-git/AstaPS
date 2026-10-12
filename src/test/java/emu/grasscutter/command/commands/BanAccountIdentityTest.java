package emu.grasscutter.command.commands;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.player.Player;
import org.junit.jupiter.api.Test;

class BanAccountIdentityTest {
    @Test void onlineBanUsesTheSessionAccountRatherThanAdvancingADetachedCopy() throws Exception {
        var selected = new Account(); selected.setId("42");
        var live = new Account(); live.setId("42");
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var player = (Online)((sun.misc.Unsafe)field.get(null)).allocateInstance(Online.class);
        player.account = live;
        assertSame(live, BanCommand.canonicalBanAccount(selected, player));
        assertSame(selected, BanCommand.canonicalBanAccount(selected, null));
        live.setId("other");
        assertSame(selected, BanCommand.canonicalBanAccount(selected, player));
    }
    static class Online extends Player {
        Account account;
        @Override public Account getAccount() { return account; }
    }
}
