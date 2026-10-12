package emu.grasscutter.game.gacha;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.player.Player;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

/** Exercises the client-facing packet without a server, database or real account. */
@ExtendWith(ServerResourceFixture.class)
final class GachaBannerPresentationTest {
    private Player player() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var player = (Player) ((Unsafe) field.get(null)).allocateInstance(Player.class);
        var account = new Account();
        account.setSessionKey("fixture-not-an-account-token");
        player.setAccount(account);
        var info = Player.class.getDeclaredField("gachaInfo");
        info.setAccessible(true);
        info.set(player, new PlayerGachaInfo());
        return player;
    }

    @Test
    void weaponPacketLabelsFeaturedSignatureWeaponsAsWeapons() throws Exception {
        var banner = new Gson().fromJson("""
                {"bannerType":"WEAPON","gachaType":302,"scheduleId":7200,
                 "prefabPath":"GachaShowPanel_A231","rateUpItems5":[12502,14515],
                 "rateUpItems4":[11401,12401,13407,14401,15401],
                 "fallbackItems5Pool1":[]}
                """, GachaBanner.class);
        banner.onLoad();
        var packet = banner.toProto(player());
        assertEquals(List.of(12502, 14515), packet.getDisplayUp5ItemListList());
        assertEquals(List.of(12502, 14515), packet.getGachaUpInfoList(0).getItemIdListList());
        assertEquals(2, packet.getGachaUpInfoList(0).getItemParentType(),
                "The client must receive weapon IDs as weapons, not avatar materials");
    }

    @Test
    void chronicledPacketSeparatesCharacterAndWeaponCategories() throws Exception {
        var banner = new Gson().fromJson("""
                {"bannerType":"CHRONICLE","gachaType":500,"scheduleId":7300,
                 "epitomizedPath":true,"autoStripRateUpFromFallback":false,
                 "prefabPath":"GachaShowPanel_N?5","previewPrefabPath":"UI_Tab_GachaShowPanel_N5",
                 "rateUpItems5":[1016,1003,12502],"rateUpItems4":[1021,1006,1015,11401]}
                """, GachaBanner.class);
        banner.onLoad();
        var packet = banner.toProto(player());
        assertEquals(List.of(1016,1003,12502), packet.getDisplayChronicle5ItemListList());
        for (var group : packet.getGachaUpInfoListList()) {
            for (int id : group.getItemIdListList()) {
                assertEquals(id < 10000 ? 1 : 2, group.getItemParentType(),
                        "Mixed Chronicled pools must identify each item's actual category");
            }
        }
    }

    @Test
    void standaloneWeaponChronicleRetainsSelectionAcrossReopens() throws Exception {
        var banner = new Gson().fromJson("""
                {"bannerType":"CHRONICLE","gachaType":500,"scheduleId":7300,
                 "epitomizedPath":true,"epitomizedTarget":"WEAPON","pityGroup":"CHRONICLE_WEAPON",
                 "prefabPath":"GachaShowPanel_N?5","previewPrefabPath":"UI_Tab_GachaShowPanel_N5",
                 "rateUpItems5":[12514,14517,11516,14516,12513],"rateUpItems4":[11401],
                 "epitomizedItems5":[12514,14517,11516,14516,12513],
                 "fallbackItems5Pool1":[],"fallbackItems4Pool1":[]}
                """, GachaBanner.class);
        banner.onLoad();
        var player = player();
        var info = player.getGachaInfo().getBannerInfo(banner);
        info.setWishItemId(12514);
        for (int reopen = 0; reopen < 3; reopen++) {
            var packet = banner.toProto(player);
            assertEquals(12514, packet.getWishItemId());
            assertEquals(12514, info.getWishItemId());
            assertEquals(List.of(12514,14517,11516,14516,12513), packet.getDisplayChronicle5ItemListList());
        }
        assertEquals(0, player.getGachaInfo().getChronicleWeaponBanner().getWishItemId(),
                "A standalone selection must not migrate into the old linked-tab record");
    }

    @Test
    void characterPacketLabelsFeaturedFourStarsAsCharacters() throws Exception {
        var banner = new Gson().fromJson("""
                {"bannerType":"CHARACTER2","gachaType":400,"scheduleId":7101,
                 "prefabPath":"GachaShowPanel_A022","rateUpItems5":[1016],
                 "rateUpItems4":[1032,1025,1043],"fallbackItems5Pool2":[]}
                """, GachaBanner.class);
        banner.onLoad();
        var packet = banner.toProto(player());
        assertEquals(List.of(1016), packet.getDisplayUp5ItemListList());
        assertEquals(1, packet.getGachaUpInfoList(1).getItemParentType(),
                "The client must receive character IDs as avatar materials, not weapons");
    }
}
