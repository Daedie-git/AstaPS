package emu.grasscutter.game.gacha;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.player.Player;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

/** The actual roll-pool constructor, with no currency, inventory or database access. */
@ExtendWith(ServerResourceFixture.class)
final class ChronicledWishPoolsTest {
    private Object pools(int choice) throws Exception {
        var banner = new Gson().fromJson("""
                {"bannerType":"CHRONICLE","gachaType":500,"scheduleId":7300,
                 "prefabPath":"GachaShowPanel_N?5","previewPrefabPath":"UI_Tab_GachaShowPanel_N5",
                 "epitomizedPath":true,"autoStripRateUpFromFallback":false,
                 "rateUpItems5":[1016,1003,12502,11501],"rateUpItems4":[1021,11401],
                 "fallbackItems5Pool1":[1016,1003],"fallbackItems5Pool2":[12502,11501]}
                """, GachaBanner.class);
        return pools(banner, choice);
    }

    private Object pools(GachaBanner banner, int choice) throws Exception {
        banner.onLoad();
        var player = allocate(Player.class);
        var info = new PlayerGachaInfo();
        info.getBannerInfo(banner).setWishItemId(choice);
        var field = Player.class.getDeclaredField("gachaInfo");field.setAccessible(true);field.set(player,info);
        var type = Class.forName(GachaSystem.class.getName()+"$BannerPools");
        var constructor = type.getDeclaredConstructor(GachaSystem.class,GachaBanner.class,Player.class);
        constructor.setAccessible(true);
        return constructor.newInstance(allocate(GachaSystem.class),banner,player);
    }

    private int[] read(Object pools,String name) throws Exception {
        var field = pools.getClass().getDeclaredField(name);field.setAccessible(true);
        return (int[])field.get(pools);
    }

    @Test
    void characterChoiceLosesOnlyToOtherCharacters() throws Exception {
        var pools = pools(1016);
        assertArrayEquals(new int[]{1016},read(pools,"rateUpItems5"));
        assertArrayEquals(new int[]{1003},read(pools,"fallbackItems5Pool1"));
        assertArrayEquals(new int[0],read(pools,"fallbackItems5Pool2"));
    }

    @Test
    void weaponChoiceLosesOnlyToOtherWeapons() throws Exception {
        var pools = pools(12502);
        assertArrayEquals(new int[]{12502},read(pools,"rateUpItems5"));
        assertArrayEquals(new int[0],read(pools,"fallbackItems5Pool1"));
        assertArrayEquals(new int[]{11501},read(pools,"fallbackItems5Pool2"));
    }

    @Test
    void lostFiftyFiftyGivesOnePointThenGuaranteesChosenCharacter() throws Exception {
        var banner = new Gson().fromJson("""
                {"bannerType":"CHRONICLE","scheduleId":7300,"epitomizedPath":true,
                 "prefabPath":"GachaShowPanel_N?5","rateUpItems5":[1016,1003,12502,11501]}
                """, GachaBanner.class);
        banner.onLoad();
        var info = new PlayerGachaBannerInfo();info.setWishItemId(1016);
        var pool = pools(1016);
        var system = allocate(ForcedLossSystem.class);
        var method = GachaSystem.class.getDeclaredMethod("doRarePull",int[].class,int[].class,
                int[].class,int.class,GachaBanner.class,PlayerGachaBannerInfo.class);
        method.setAccessible(true);
        Object[] args = {read(pool,"rateUpItems5"),read(pool,"fallbackItems5Pool1"),
                read(pool,"fallbackItems5Pool2"),5,banner,info};
        var first = method.invoke(system,args);
        var itemId = first.getClass().getDeclaredMethod("itemId");itemId.setAccessible(true);
        assertEquals(1003,itemId.invoke(first));
        assertEquals(1,info.getFailedChosenItemPulls());
        assertEquals(1016,itemId.invoke(method.invoke(system,args)));
        assertEquals(0,info.getFailedChosenItemPulls());
    }

    @Test
    void fiveWeaponChronicleLosesOnlyToTheOtherFourThenGuaranteesSelection() throws Exception {
        var banner = new Gson().fromJson("""
                {"bannerType":"CHRONICLE","scheduleId":7300,"epitomizedPath":true,
                 "epitomizedTarget":"WEAPON","pityGroup":"CHRONICLE_WEAPON",
                 "prefabPath":"GachaShowPanel_N?5","autoStripRateUpFromFallback":false,
                 "rateUpItems5":[12514,14517,11516,14516,12513],
                 "epitomizedItems5":[12514,14517,11516,14516,12513],
                 "fallbackItems5Pool1":[],"fallbackItems5Pool2":[12514,14517,11516,14516,12513],
                 "fallbackItems4Pool1":[],"fallbackItems4Pool2":[11401]}
                """, GachaBanner.class);
        var pool = pools(banner, 12514);
        assertArrayEquals(new int[]{12514}, read(pool,"rateUpItems5"));
        assertArrayEquals(new int[0], read(pool,"fallbackItems5Pool1"));
        assertArrayEquals(new int[]{14517,11516,14516,12513}, read(pool,"fallbackItems5Pool2"));
        assertArrayEquals(new int[0], read(pool,"fallbackItems4Pool1"));
        var info = new PlayerGachaBannerInfo();info.setWishItemId(12514);
        var system = allocate(ForcedLossSystem.class);
        var method = GachaSystem.class.getDeclaredMethod("doRarePull",int[].class,int[].class,
                int[].class,int.class,GachaBanner.class,PlayerGachaBannerInfo.class);
        method.setAccessible(true);
        Object[] args = {read(pool,"rateUpItems5"),read(pool,"fallbackItems5Pool1"),
                read(pool,"fallbackItems5Pool2"),5,banner,info};
        var first = method.invoke(system,args);
        var itemId = first.getClass().getDeclaredMethod("itemId");itemId.setAccessible(true);
        assertEquals(12513,itemId.invoke(first));
        assertEquals(1,info.getFailedChosenItemPulls());
        assertEquals(12514,itemId.invoke(method.invoke(system,args)));
        assertEquals(0,info.getFailedChosenItemPulls());
    }

    private static final class ForcedLossSystem extends GachaSystem {
        private ForcedLossSystem() { super(null); }
        @Override public int randomRange(int min,int max) { return max; }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(((Unsafe)field.get(null)).allocateInstance(type));
    }
}
