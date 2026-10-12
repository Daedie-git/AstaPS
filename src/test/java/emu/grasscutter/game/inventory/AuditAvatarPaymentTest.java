package emu.grasscutter.game.inventory;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.data.excels.avatar.AvatarData;
import emu.grasscutter.data.excels.avatar.AvatarPromoteData;
import emu.grasscutter.game.avatar.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ItemUseAction.ItemUseAddExp;
import emu.grasscutter.game.systems.InventorySystem;
import emu.grasscutter.net.proto.ItemParamOuterClass.ItemParam;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
class AuditAvatarPaymentTest {
    static final int ID=998994;
    P player; Inventory inv; SystemUnderTest system; ItemData previous; AvatarPromoteData previousPromote;
    Int2ObjectMap<AvatarPromoteData> promotes;
    @BeforeEach void setup() throws Exception {
        player=allocate(P.class);AuditCurrencyCancellationTest.initializeProperties(player);player.setMora(100000);inv=new Inventory(player);player.inventory=inv;
        var avatar=allocate(Avatar.class);
        var data=new Gson().fromJson("{\"id\":10000001,\"avatarPromoteId\":3999}",AvatarData.class);
        field(Avatar.class,"avatarData").set(avatar,data);
        player.storage=new AvatarStorage(player){@Override public Avatar getAvatarByGuid(long guid){return avatar;}};
        @SuppressWarnings("unchecked") var map=(Int2ObjectMap<AvatarPromoteData>)field(GameData.class,"avatarPromoteDataMap").get(null);
        promotes=map;previousPromote=map.put(3999<<8,new AvatarPromoteData());
        previous=GameData.getItemDataMap().get(ID);
        var book=new Gson().fromJson("{\"id\":998994,\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXP_FRUIT\",\"stackLimit\":100}",ItemData.class);
        field(ItemData.class,"itemUseActions").set(book,List.of(new ItemUseAddExp(new String[]{"100"})));
        GameData.getItemDataMap().put(ID,book); system=new SystemUnderTest();
    }
    @AfterEach void restore(){
        if(previous==null)GameData.getItemDataMap().remove(ID);else GameData.getItemDataMap().put(ID,previous);
        if(previousPromote==null)promotes.remove(3999<<8);else promotes.put(3999<<8,previousPromote);
    }
    @Test void duplicateOneBookRequestDoesNotGrantTwoBooksXp() {
        seed(1);system.upgradeAvatar(player,1L,List.of(param(1),param(1)));
        assertEquals(0,system.xp);assertEquals(1,inv.getItemCountById(ID));assertEquals(100000,player.getMora());
    }
    @Test void twoBooksPayExactlyTwoAndGrantExactXp() {
        seed(2);system.upgradeAvatar(player,1L,List.of(param(1),param(1)));
        assertEquals(200,system.xp);assertEquals(0,inv.getItemCountById(ID));assertEquals(99960,player.getMora());
    }
    @Test void wrappingXpRequestDoesNotMutateBooksOrMora() {
        seed(2);system.upgradeAvatar(player,1L,List.of(param(Integer.MAX_VALUE)));
        assertEquals(0,system.xp);assertEquals(2,inv.getItemCountById(ID));assertEquals(100000,player.getMora());
    }
    private void seed(int count){var item=new GameItem(GameData.getItemDataMap().get(ID),count){@Override public void save(){}};inv.getItems().put(item.getGuid(),item);inv.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(item);}
    private ItemParam param(int count){return ItemParam.newBuilder().setItemId(ID).setCount(count).build();}
    private static java.lang.reflect.Field field(Class<?> c,String n)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);return f;}
    private static <T>T allocate(Class<T> c)throws Exception{return c.cast(((Unsafe)field(Unsafe.class,"theUnsafe").get(null)).allocateInstance(c));}
    static class P extends AuditInventoryTest.TestPlayer { Inventory inventory;AvatarStorage storage;@Override public Inventory getInventory(){return inventory;}@Override public AvatarStorage getAvatars(){return storage;}}
    static class SystemUnderTest extends InventorySystem {int xp;SystemUnderTest(){super(null);}@Override public void upgradeAvatar(Player p,Avatar a,AvatarPromoteData d,int gain){xp+=gain;}}
}
