package emu.grasscutter.game.inventory;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.game.combine.CombineManger;
import emu.grasscutter.game.managers.cooking.*;
import emu.grasscutter.game.managers.forging.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.ForgeStartReqOuterClass.ForgeStartReq;
import emu.grasscutter.net.proto.CompoundQueueDataOuterClass.CompoundQueueData;
import emu.grasscutter.net.proto.PlayerCompoundMaterialReqOuterClass.PlayerCompoundMaterialReq;
import emu.grasscutter.net.proto.TakeCompoundOutputReqOuterClass.TakeCompoundOutputReq;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
class AuditInventoryCallersTest {
    static final int ID = 998991;
    P player;
    Inventory inv;
    ForgeData oldForge;
    CompoundData oldCompound;
    ItemData oldItem;
    @BeforeEach void setup() throws Exception {
        var f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        player = (P) ((Unsafe) f.get(null)).allocateInstance(P.class);
        AuditCurrencyCancellationTest.initializeProperties(player);
        player.forges = new ArrayList<>(); player.compounds = new HashMap<>(); player.sent = new ArrayList<>();
        player.points = 100; player.recipes = new HashMap<>(); player.combines = new HashSet<>();
        inv = new Inventory(player); player.inventory = inv;
        oldForge = GameData.getForgeDataMap().get(ID);
        oldCompound = GameData.getCompoundDataMap().get(ID);
        oldItem = GameData.getItemDataMap().get(ID);
        GameData.getForgeDataMap().put(ID, new Gson().fromJson("{\"id\":998991,\"resultItemId\":998991,\"resultItemCount\":1,\"forgePoint\":1,\"forgeTime\":10,\"scoinCost\":1,\"materialItems\":[]}", ForgeData.class));
        GameData.getCompoundDataMap().put(ID, new Gson().fromJson("{\"id\":998991,\"isDefaultUnlocked\":true,\"costTime\":10,\"queueSize\":10,\"inputVec\":[],\"outputVec\":[{\"id\":998991,\"count\":1}]}", CompoundData.class));
        GameData.getItemDataMap().put(ID, material(ID));
        CookingCompoundManager.initialize();
    }
    @AfterEach void restore() {
        if(oldForge == null) GameData.getForgeDataMap().remove(ID); else GameData.getForgeDataMap().put(ID, oldForge);
        if(oldCompound == null) GameData.getCompoundDataMap().remove(ID); else GameData.getCompoundDataMap().put(ID, oldCompound);
        if(oldItem == null) GameData.getItemDataMap().remove(ID); else GameData.getItemDataMap().put(ID, oldItem);
        CookingCompoundManager.initialize();
    }
    @Test void unpaidForgeNeverCreatesRefundableQueue() {
        new ForgingManager(player).handleForgeStartReq(ForgeStartReq.newBuilder().setForgeId(ID).setForgeCount(1).build());
        assertTrue(player.forges.isEmpty()); assertEquals(100, player.points); assertEquals(1, player.sent.size());
    }
    @Test void invalidForgeCountPreservesPointsAndQueue() {
        new ForgingManager(player).handleForgeStartReq(ForgeStartReq.newBuilder().setForgeId(ID).setForgeCount(-1).build());
        assertTrue(player.forges.isEmpty()); assertEquals(100, player.points);
    }
    @Test void firstCompoundBatchCannotExceedCapacity() {
        new CookingCompoundManager(player).handlePlayerCompoundMaterialReq(PlayerCompoundMaterialReq.newBuilder().setCompoundId(ID).setCount(11).build());
        assertTrue(player.compounds.isEmpty());
    }
    @Test void negativeAppendCannotShrinkQueue() {
        var queue = new ActiveCookCompoundData(ID,10,5,100); player.compounds.put(ID,queue);
        new CookingCompoundManager(player).handlePlayerCompoundMaterialReq(PlayerCompoundMaterialReq.newBuilder().setCompoundId(ID).setCount(-1).build());
        assertEquals(5, queue.getTotalCount());
    }
    @Test void canonicalQueueFieldsRemainDistinct() throws Exception {
        int now = emu.grasscutter.utils.Utils.getCurrentSeconds();
        var queue = new ActiveCookCompoundData(ID,10,5,now-20); player.compounds.put(ID,queue);
        var manager = new CookingCompoundManager(player);
        var m = CookingCompoundManager.class.getDeclaredMethod("getCompoundQueueData"); m.setAccessible(true);
        @SuppressWarnings("unchecked") var data = ((List<CompoundQueueData>)m.invoke(manager)).get(0);
        assertEquals(3,data.getWaitCount()); assertEquals(2,data.getOutputCount()); assertEquals(now+10,data.getOutputTime());
        assertEquals(1, CompoundQueueData.getDescriptor().findFieldByName("wait_count").getNumber());
        assertEquals(2, CompoundQueueData.getDescriptor().findFieldByName("output_count").getNumber());
        assertEquals(7, CompoundQueueData.getDescriptor().findFieldByName("output_time").getNumber());
    }
    @Test void fullInventoryDoesNotConsumeCompoundQueue() {
        inv.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(0));
        var queue = new ActiveCookCompoundData(ID,10,5,0); player.compounds.put(ID,queue);
        new CookingCompoundManager(player).handleTakeCompoundOutputReq(TakeCompoundOutputReq.newBuilder().setCompoundId(ID).build());
        assertEquals(5, queue.getTotalCount()); assertTrue(player.compounds.containsKey(ID));
    }
    @Test void fullInventoryCookingRejectsBeforePaymentAndProficiency() {
        var previous = GameData.getCookRecipeDataMap().put(ID,new Gson().fromJson("{\"id\":998991,\"maxProficiency\":10,\"inputVec\":[{\"id\":202,\"count\":1}],\"qualityOutputVec\":[{\"id\":998991,\"count\":1}]}",CookRecipeData.class));
        try {
            player.setMora(100);
            inv.createInventoryTab(ItemType.ITEM_MATERIAL,new MaterialInventoryTab(0));
            new CookingManager(player).handlePlayerCookReq(emu.grasscutter.net.proto.PlayerCookReqOuterClass.PlayerCookReq.newBuilder().setRecipeId(ID).setCookCount(1).build());
            assertEquals(100,player.getMora()); assertTrue(player.recipes.isEmpty());
        } finally {if(previous==null)GameData.getCookRecipeDataMap().remove(ID);else GameData.getCookRecipeDataMap().put(ID,previous);}
    }
    @Test void combinePreflightsRawOutputBeforeDebit() {
        var previous=GameData.getCombineDataMap().put(ID,new Gson().fromJson("{\"combineId\":998991,\"resultItemId\":998991,\"resultItemCount\":11,\"scoinCost\":1,\"materialItems\":[]}",CombineData.class));
        try {
            player.setMora(100);
            assertNull(new CombineManger(null).combineItem(player,ID,1));
            assertEquals(100,player.getMora());assertTrue(inv.getItems().isEmpty());
        } finally {if(previous==null)GameData.getCombineDataMap().remove(ID);else GameData.getCombineDataMap().put(ID,previous);}
    }
    @Test void compoundRetriesAfterFreeingStackHeadroomExactlyOnce() {
        inv.createInventoryTab(ItemType.ITEM_MATERIAL,new MaterialInventoryTab(1));
        var stack=new MemoryItem(material(ID),10);
        inv.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(stack);
        var queue=new ActiveCookCompoundData(ID,10,5,0); player.compounds.put(ID,queue);
        var manager=new CookingCompoundManager(player);
        var request=TakeCompoundOutputReq.newBuilder().setCompoundId(ID).build();
        manager.handleTakeCompoundOutputReq(request);
        assertEquals(5,queue.getTotalCount()); assertEquals(10,stack.getCount());
        stack.setCount(5);
        manager.handleTakeCompoundOutputReq(request);
        assertFalse(player.compounds.containsKey(ID)); assertEquals(10,stack.getCount());
        manager.handleTakeCompoundOutputReq(request);
        assertEquals(10,stack.getCount());
    }
    @Test void duplicateStrongboxGuidRejectedWithoutConsumption() throws Exception {
        var field = CombineManger.class.getDeclaredField("reliquaryDecomposeData"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var drops = (it.unimi.dsi.fastutil.ints.Int2ObjectMap<List<Integer>>)field.get(null);
        var previous = drops.put(ID, List.of(ID));
        try {
            var recording = new Inventory(player) {
                @Override public boolean addItem(GameItem item) { fail("Invalid strongbox request attempted output grant"); return false; }
            };
            player.inventory = recording;
            var input = new GameItem(material(ID),1); recording.getItems().put(1L,input);
            new CombineManger(null).decomposeReliquaries(player,ID,1,List.of(1L,1L,1L));
            assertSame(input, recording.getItemByGuid(1L)); assertEquals(1,input.getCount());
        } finally { if(previous == null) drops.remove(ID); else drops.put(ID, previous); }
    }
    @Test void strongboxRejectsMaterialLowRarityLockedAndEquippedInputs() throws Exception {
        var field=CombineManger.class.getDeclaredField("reliquaryDecomposeData");field.setAccessible(true);
        @SuppressWarnings("unchecked") var drops=(it.unimi.dsi.fastutil.ints.Int2ObjectMap<List<Integer>>)field.get(null);
        var previous=drops.put(ID,List.of(ID));
        try {
            for(int mode=0;mode<4;mode++) {
                var recording=new Inventory(player){@Override public boolean addItem(GameItem item){fail("Ineligible strongbox inputs reached grant");return false;}};
                player.inventory=recording;
                ItemData data=mode==0?material(ID):new Gson().fromJson("{\"id\":998991,\"itemType\":\"ITEM_RELIQUARY\",\"rankLevel\":"+(mode==1?4:5)+"}",ItemData.class);
                for(long guid=1;guid<=3;guid++) {
                    var item=new GameItem(data,1);item.setLocked(mode==2);if(mode==3)item.setEquipCharacter(10000001);
                    recording.getItems().put(guid,item);
                }
                new CombineManger(null).decomposeReliquaries(player,ID,1,List.of(1L,2L,3L));
                assertEquals(3,recording.getItems().size());
            }
        } finally {if(previous==null)drops.remove(ID);else drops.put(ID,previous);}
    }
    @Test void unsafeEffectsAreRejectedBeforeOrdinaryOrCurrencyMutation() {
        var effect = new Gson().fromJson("{\"id\":998992,\"itemType\":\"ITEM_MATERIAL\",\"useOnGain\":true}",ItemData.class);
        assertFalse(inv.tryAddItems(List.of(new GameItem(material(ID),1),new GameItem(effect,1)),null));
        assertEquals(0, inv.getItems().size());
        assertFalse(inv.canAddItemParamDatas(List.of(new emu.grasscutter.data.common.ItemParamData(ID,11))));
    }
    @Test void duplicateBatchCapacityCountsEveryOutput() {
        inv.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(1));
        assertFalse(inv.canAddItems(List.of(new GameItem(material(ID),6),new GameItem(material(ID),5))));
        assertEquals(0, inv.getItems().size());
    }
    @Test void fullTabAcceptsExistingStackHeadroomButRejectsOverflowAndMixedBatch() {
        inv.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(1));
        inv.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(new GameItem(material(ID),9));
        assertTrue(inv.canAddItems(List.of(new GameItem(material(ID),1))));
        assertFalse(inv.canAddItems(List.of(new GameItem(material(ID),2))));
        assertFalse(inv.tryAddItems(List.of(new GameItem(material(ID),1),new GameItem(material(ID+1),1)), null));
        assertEquals(9,inv.getItemById(ID).getCount());
    }
    private static ItemData material(int id) { return new Gson().fromJson("{\"id\":"+id+",\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":10}",ItemData.class); }
    static class MemoryItem extends GameItem {
        MemoryItem(ItemData data,int count){super(data,count);}
        @Override public void save(){}
    }
    static class P extends Player {
        Inventory inventory; List<ActiveForgeData> forges; Map<Integer,ActiveCookCompoundData> compounds; List<BasePacket> sent; int points; Map<Integer,Integer> recipes; Set<Integer> combines;
        @Override public Inventory getInventory(){return inventory;}
        @Override public int getLevel(){return 1;}
        @Override public int getForgePoints(){return points;}
        @Override public boolean setForgePoints(int n){points=n;return true;}
        @Override public List<ActiveForgeData> getActiveForges(){return forges;}
        @Override public Map<Integer,ActiveCookCompoundData> getActiveCookCompounds(){return compounds;}
        @Override public Map<Integer,Integer> getUnlockedRecipies(){return recipes;}
        @Override public Set<Integer> getUnlockedCombines(){return combines;}
        @Override public void sendPacket(BasePacket packet){sent.add(packet);}
    }
}
