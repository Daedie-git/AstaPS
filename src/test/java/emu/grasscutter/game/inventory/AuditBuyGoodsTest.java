package emu.grasscutter.game.inventory;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.game.shop.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.BuyGoodsReqOuterClass.BuyGoodsReq;
import emu.grasscutter.net.proto.ShopGoodsOuterClass.ShopGoods;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.recv.HandlerBuyGoodsReq;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
class AuditBuyGoodsTest {
    static final int ID=998995;
    P player; S session; ShopInfo good; ItemData previous;
    @BeforeEach void setup() throws Exception {
        player=allocate(P.class); AuditCurrencyCancellationTest.initializeProperties(player); player.setMora(100); player.inventory=new Inventory(player);
        var shop=allocate(Shop.class); shop.goods=new Int2ObjectOpenHashMap<>(); shop.artifacts=new ArtifactShop();
        good=new ShopInfo(); good.setGoodsId(ID); good.setGoodsItem(new ItemParamData(ID,2)); good.setScoin(1);
        shop.goods.put(1,List.of(good));
        var server=allocate(GameServer.class);
        var shopField=GameServer.class.getDeclaredField("shopSystem"); shopField.setAccessible(true); shopField.set(server,shop);
        session=new S(server,player);
        previous=GameData.getItemDataMap().get(ID);
        GameData.getItemDataMap().put(ID,new Gson().fromJson("{\"id\":998995,\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":10}",ItemData.class));
    }
    @AfterEach void restore(){if(previous==null)GameData.getItemDataMap().remove(ID);else GameData.getItemDataMap().put(ID,previous);}
    @Test void overflowingOutputDoesNotChargeOrConsumeLimit() throws Exception {
        good.setScoin(0); buy(Integer.MAX_VALUE); assertEquals(100,player.getMora()); assertEquals(0,player.bought); assertTrue(player.inventory.getItems().isEmpty());
    }
    @Test void fullTabDoesNotChargeOrConsumeLimit() throws Exception {
        player.inventory.createInventoryTab(ItemType.ITEM_MATERIAL,new MaterialInventoryTab(0));
        buy(1); assertEquals(100,player.getMora()); assertEquals(0,player.bought); assertTrue(player.inventory.getItems().isEmpty());
    }
    @Test void rawStackExcessIsRejectedInsteadOfTruncated() throws Exception {
        good.setGoodsItem(new ItemParamData(ID,11)); buy(1);
        assertEquals(100,player.getMora()); assertEquals(0,player.bought);
    }
    @Test void barterExhaustedIngredientFreesOutputSlotExactlyOnce() throws Exception {
        barter(3, true);
    }
    @Test void barterLeftoverIngredientRejectsUnchanged() throws Exception {
        barter(4, false);
    }
    private void barter(int inputCount, boolean succeeds) throws Exception {
        int inputId = ID + 20;
        var previousInput = GameData.getItemDataMap().put(inputId, new Gson().fromJson(
                "{\"id\":" + inputId + ",\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":10}", ItemData.class));
        try {
            AuditExchangeTest.initializeCodex(player);
            player.inventory.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(1));
            var input = AuditExchangeTest.seed(player.inventory, inputId, inputCount);
            good.setCostItemList(List.of(new ItemParamData(inputId, 3)));
            good.setBuyLimit(1);
            buy(1);
            assertEquals(succeeds ? 0 : inputCount, input.getCount());
            assertEquals(succeeds ? 2 : 0, player.inventory.getItemCountById(ID));
            assertEquals(succeeds ? 99 : 100, player.getMora());
            assertEquals(succeeds ? 1 : 0, player.bought);
            buy(1);
            assertEquals(succeeds ? 2 : 0, player.inventory.getItemCountById(ID));
            assertEquals(succeeds ? 99 : 100, player.getMora());
            assertEquals(succeeds ? 1 : 0, player.bought);
        } finally {
            if (previousInput == null) GameData.getItemDataMap().remove(inputId);
            else GameData.getItemDataMap().put(inputId, previousInput);
        }
    }
    private void buy(int count) throws Exception {
        var req=BuyGoodsReq.newBuilder().setShopType(1).setBuyCount(count).setGoods(ShopGoods.newBuilder().setGoodsId(ID)).build();
        new HandlerBuyGoodsReq().handle(session,new byte[0],req.toByteArray());
    }
    private static <T> T allocate(Class<T> type) throws Exception {var f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return type.cast(((Unsafe)f.get(null)).allocateInstance(type));}
    static class P extends AuditInventoryTest.TestPlayer {
        Inventory inventory; int bought; ShopLimit limit;
        @Override public Inventory getInventory(){return inventory;}
        @Override public ShopLimit getGoodsLimit(int id){return limit;}
        @Override public void addShopLimit(int id,int count,int refresh){bought+=count;limit=new ShopLimit();limit.setHasBoughtInPeriod(bought);limit.setHasBought(bought);}
        @Override public int getPrimogems(){return 0;}
        @Override public int getCrystals(){return 0;}
        @Override public void save(){}
    }
    static class Shop extends ShopSystem {
        Int2ObjectMap<List<ShopInfo>> goods; ArtifactShop artifacts;
        Shop(){super(null);}
        @Override public Int2ObjectMap<List<ShopInfo>> getShopData(){return goods;}
        @Override public ArtifactShop getArtifactShop(){return artifacts;}
    }
    static class S extends GameSession {
        P player;
        S(GameServer server,P player){super(server);this.player=player;}
        @Override public P getPlayer(){return player;}
        @Override public void send(BasePacket p){}
    }
}
