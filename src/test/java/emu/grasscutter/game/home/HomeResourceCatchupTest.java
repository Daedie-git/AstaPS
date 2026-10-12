package emu.grasscutter.game.home;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.server.game.GameSession;

import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.List;

class HomeResourceCatchupTest {
    @Test
    void tickAndLoginAccrueSameThreeOverdueHours() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var player = (QuietPlayer) ((Unsafe) field.get(null)).allocateInstance(QuietPlayer.class);
        var tick = new RecordingHome();
        var login = new RecordingHome();
        player.home = tick;
        int now = (int) (System.currentTimeMillis() / 1000);
        tick.setNextUpdateTime(now - 7200);
        login.setNextUpdateTime(now - 7200);
        tick.setLastUpdatedTime(now - 10800);
        login.setLastUpdatedTime(now - 10800);
        tick.updateHourlyResources(player);
        var check = GameHome.class.getDeclaredMethod("checkAccumulatedResources", Player.class);
        check.setAccessible(true);
        check.invoke(login, player);
        assertEquals(90, tick.coin);
        assertEquals(login.coin, tick.coin);
        tick.updateHourlyResources(player);
        assertEquals(90, tick.coin);
    }

    @Test
    void uninitializedHomeDoesNotAccrueSinceEpoch() throws Exception {
        var home = new RecordingHome();
        var check = GameHome.class.getDeclaredMethod("checkAccumulatedResources", Player.class);
        check.setAccessible(true);
        check.invoke(home, new Object[] {null});
        assertEquals(0, home.coin);
    }

    static class RecordingHome extends GameHome {
        int coin;

        RecordingHome() {
            super(null, 0, null, 1, 0, 0, 0, 0, 0, null, null, null, null, 0, null, null);
        }

        @Override
        public List<Integer> getComfortResources(Player p) {
            return List.of(30, 5);
        }

        @Override
        public void storeResources(Player p, int coin, int fetter) {
            this.coin += coin;
        }

        @Override
        public void save() {}

        @Override
        public int getMaxCoin(int level) {
            return 10000;
        }

        @Override
        public int getMaxFetter(int level) {
            return 10000;
        }
    }

    static class QuietPlayer extends Player {
        GameHome home;

        @Override
        public GameHome getHome() {
            return home;
        }

        @Override
        public GameSession getSession() {
            return new QuietSession();
        }
    }

    static class QuietSession extends GameSession {
        QuietSession() {
            super(null);
        }

        @Override
        public void send(BasePacket p) {}
    }
}
