package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.friends.FriendsList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class LogoutPersistenceFailureTest {
    @Test void rejectedPlayerSaveStillAttemptsAvatarAndFriendSaves() throws Exception {
        var player = fixture();
        assertThrows(RuntimeException.class, player::saveLogoutState);
        assertEquals(1, player.avatar.saves);
        assertEquals(1, player.friends.saves);
    }
    @Test void rejectedAvatarSaveDoesNotSkipOtherAvatars() throws Exception {
        var p = fixture(); p.avatar.fail = true;
        var other = allocate(A.class); var entity = allocate(EntityAvatar.class);
        set(entity, EntityAvatar.class, "avatar", other);
        set(p.team, TeamManager.class, "avatars", List.of(p.team.getActiveTeam().get(0), entity));
        assertThrows(RuntimeException.class, () -> p.team.saveAvatars());
        assertEquals(1, other.saves);
    }
    static P fixture() throws Exception {
        var p = allocate(P.class); p.avatar = allocate(A.class); p.friends = new F(p);
        p.team = allocate(TeamManager.class);
        var entity = allocate(EntityAvatar.class); set(entity, EntityAvatar.class, "avatar", p.avatar);
        set(p.team, TeamManager.class, "avatars", List.of(entity));
        return p;
    }
    static class P extends Player {
        A avatar; F friends; TeamManager team;
        @Override public void save() { throw new RejectedExecutionException("ordered budget full"); }
        @Override public TeamManager getTeamManager() { return team; }
        @Override public FriendsList getFriendsList() { return friends; }
    }
    static class A extends Avatar {
        int saves; boolean fail;
        A() { super(0); }
        @Override public void save() { if (fail) throw new RejectedExecutionException("avatar budget full"); saves++; }
    }
    static class F extends FriendsList {
        int saves;
        F(Player player) { super(player); }
        @Override public void save() { saves++; }
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe)field.get(null)).allocateInstance(type));
    }
    static void set(Object object, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
}
