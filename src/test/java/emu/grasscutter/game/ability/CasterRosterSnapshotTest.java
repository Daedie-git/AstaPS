package emu.grasscutter.game.ability;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.binout.AbilityData;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.player.*;
import emu.grasscutter.utils.JsonUtils;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
final class CasterRosterSnapshotTest {
    @Test void casterLookupDoesNotIterateAChangingLiveRoster() throws Exception {
        var player = allocate(P.class);
        player.team = new TeamManager();
        var roster = player.team.getActiveTeam();
        var first = member("Xiao");
        var caster = member("Albedo");
        var removed = member("Diluc");
        roster.addAll(List.of(first, caster, removed));
        first.onRead = () -> roster.remove(removed);
        var ability = allocate(Ability.class);
        var data = new AbilityData();
        data.abilityName = "Albedo_ElementalArt";
        set(ability, Ability.class, "data", data);
        set(ability, Ability.class, "playerOwner", player);
        assertSame(caster, assertDoesNotThrow(ability::getCasterEntity));
        assertEquals(List.of(first, caster), roster);
    }
    private static E member(String name) throws Exception {
        var entity = allocate(E.class);
        entity.avatar = allocate(A.class);
        entity.avatar.data = JsonUtils.decode("{\"iconName\":\"UI_AvatarIcon_" + name + "\"}", AvatarData.class);
        entity.avatar.data.buildEmbryo();
        return entity;
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }
    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static final class P extends Player {
        TeamManager team;
        @Override public TeamManager getTeamManager() { return team; }
    }
    static final class A extends Avatar {
        AvatarData data;
        @Override public AvatarData getAvatarData() { return data; }
    }
    static final class E extends EntityAvatar {
        A avatar; Runnable onRead;
        E() { super(null, null); }
        @Override public Avatar getAvatar() {
            if (onRead != null) { var action = onRead; onRead = null; action.run(); }
            return avatar;
        }
    }
}
