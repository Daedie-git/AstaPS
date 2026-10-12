package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.game.avatar.*;
import emu.grasscutter.game.expedition.*;
import emu.grasscutter.server.game.GameServer;
import it.unimi.dsi.fastutil.longs.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class ExpeditionOwnershipTest {
    @Test
    void inventedAvatarCannotCreateAssignment() throws Exception {
        var player = player();
        player.addExpeditionInfo(999999, 101, 4, 1000);
        assertTrue(player.exps.isEmpty());
    }

    @Test
    void validBatchStartsAndOccupiedAssignmentCannotBeOverwritten() throws Exception {
        var player = player();
        assertTrue(ExpeditionHelper.startAll(player, List.of(new ExpeditionHelper.Assignment(1, 101, 4)), 1000));
        var original = player.exps.get(1L);
        player.addExpeditionInfo(1, 102, 4, 2000);
        assertSame(original, player.exps.get(1L));
        assertEquals(101, original.getExpId());
        assertEquals(1000, original.getStartTime());
    }

    @Test
    void duplicateAndExcessAssignmentsRejectTheEntireBatch() throws Exception {
        var player = player();
        assertFalse(ExpeditionHelper.startAll(player, List.of(new ExpeditionHelper.Assignment(1, 101, 4), new ExpeditionHelper.Assignment(1, 102, 4)), 1000));
        assertTrue(player.exps.isEmpty());
        assertFalse(ExpeditionHelper.startAll(player, List.of(new ExpeditionHelper.Assignment(1, 101, 4), new ExpeditionHelper.Assignment(2, 102, 4), new ExpeditionHelper.Assignment(3, 103, 4)), 1000));
        assertTrue(player.exps.isEmpty());
    }

    @Test
    void unsupportedDurationAndOccupiedLocationRejectWithoutMutation() throws Exception {
        var player = player();
        player.addExpeditionInfo(1, 101, 999, 1000);
        assertTrue(player.exps.isEmpty());
        player.addExpeditionInfo(1, 101, 4, 1000);
        player.addExpeditionInfo(2, 101, 4, 1000);
        assertEquals(1, player.exps.size());
    }

    private static FakePlayer player() throws Exception {
        var player = allocate(FakePlayer.class);
        player.exps = new Long2ObjectOpenHashMap<>();
        var map = Player.class.getDeclaredField("expeditionInfo");
        map.setAccessible(true);
        map.set(player, player.exps);
        player.avatars = new OwnedAvatars(player, allocate(Avatar.class));
        player.server = allocate(GameServer.class);
        var system = new ResourceExpeditions();
        var systemField = GameServer.class.getDeclaredField("expeditionSystem");
        systemField.setAccessible(true);
        systemField.set(player.server, system);
        for (int location : List.of(101, 102, 103)) {
            system.getExpeditionRewardDataList().put(location, List.of(new Gson().fromJson("{\"hourTime\":4}", ExpeditionRewardDataList.class)));
        }
        return player;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static class FakePlayer extends Player {
        Long2ObjectMap<ExpeditionInfo> exps;
        AvatarStorage avatars;
        GameServer server;
        @Override public Long2ObjectMap<ExpeditionInfo> getExpeditionInfo() { return exps; }
        @Override public AvatarStorage getAvatars() { return avatars; }
        @Override public GameServer getServer() { return server; }
        @Override public int getExpeditionLimit() { return 2; }
        @Override public void save() {}
    }

    private static class OwnedAvatars extends AvatarStorage {
        final Avatar avatar;
        OwnedAvatars(Player player, Avatar avatar) { super(player); this.avatar = avatar; }
        @Override public Avatar getAvatarByGuid(long guid) { return guid >= 1 && guid <= 3 ? avatar : null; }
    }

    private static class ResourceExpeditions extends ExpeditionSystem {
        ResourceExpeditions() { super(null); }
        @Override public synchronized void load() {}
    }
}
