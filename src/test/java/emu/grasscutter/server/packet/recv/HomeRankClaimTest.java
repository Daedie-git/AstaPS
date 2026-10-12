package emu.grasscutter.server.packet.recv;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;

import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.game.home.GameHome;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.GetHomeLevelUpRewardReqOuterClass.GetHomeLevelUpRewardReq;
import emu.grasscutter.server.game.GameSession;

import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.*;

class HomeRankClaimTest {
    @Test
    void futureHomeRankDoesNotGrantOrMark() throws Exception {
        var p = allocate(FakePlayer.class);
        p.home = GameHome.create(991);
        p.setRealmList(new HashSet<>(Set.of(1)));
        p.setHomeRewardedLevels(new HashSet<>());
        p.inv = allocate(FakeInventory.class);
        var level =
                new Gson().fromJson("{\"level\":10,\"rewardId\":991800}", HomeWorldLevelData.class);
        var old = GameData.getHomeWorldLevelDataMap().put(10, level);
        var reward = new RewardData();
        reward.rewardId = 991800;
        reward.rewardItemList = List.of(new ItemParamData(201, 10));
        var oldReward = GameData.getRewardDataMap().put(991800, reward);
        var session = new FakeSession(p);
        try {
            try {
                new HandlerGetHomeLevelUpRewardReq()
                        .handle(
                                session,
                                null,
                                GetHomeLevelUpRewardReq.newBuilder()
                                        .setLevel(10)
                                        .build()
                                        .toByteArray());
            } catch (RuntimeException ignored) {
                /* Old claim continues into unrelated home notifications after granting. */
            }
            assertEquals(0, p.inv.grants);
            assertTrue(p.getHomeRewardedLevels().isEmpty());
        } finally {
            if (old == null) GameData.getHomeWorldLevelDataMap().remove(10);
            else GameData.getHomeWorldLevelDataMap().put(10, old);
            if (oldReward == null) GameData.getRewardDataMap().remove(991800);
            else GameData.getRewardDataMap().put(991800, oldReward);
        }
    }

    static <T> T allocate(Class<T> type) throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return type.cast(((Unsafe) f.get(null)).allocateInstance(type));
    }

    static class FakePlayer extends Player {
        GameHome home;
        FakeInventory inv;

        @Override
        public GameHome getHome() {
            return home;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        @Override
        public void save() {}
    }

    static class FakeInventory extends Inventory {
        int grants;

        FakeInventory() {
            super(null);
        }

        @Override
        public void addItemParamDatas(Collection<ItemParamData> p, ActionReason r) {
            grants++;
        }

        @Override
        public boolean tryAddItemParamDatas(Collection<ItemParamData> p, ActionReason r) {
            grants++;
            return true;
        }
    }

    static class FakeSession extends GameSession {
        Player p;

        FakeSession(Player p) {
            super(null);
            this.p = p;
        }

        @Override
        public Player getPlayer() {
            return p;
        }

        @Override
        public void send(BasePacket p) {}
    }
}
