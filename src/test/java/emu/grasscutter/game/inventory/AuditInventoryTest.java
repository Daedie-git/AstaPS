package emu.grasscutter.game.inventory;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
class AuditInventoryTest {
    TestPlayer player;
    Inventory inventory;
    @BeforeEach void setup() throws Exception {
        var f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        player = (TestPlayer) ((Unsafe) f.get(null)).allocateInstance(TestPlayer.class);
        AuditCurrencyCancellationTest.initializeProperties(player);
        player.setMora(100);
        inventory = new Inventory(player);
    }
    @Test void rejectedPhysicalDebitIsDetectedBeforeAnyCurrencyMutation() {
        var data = new Gson().fromJson("{\"id\":998993,\"itemType\":\"ITEM_MATERIAL\",\"stackLimit\":10}",ItemData.class);
        inventory.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(new GameItem(data,1));
        assertFalse(inventory.payItems(List.of(new ItemParamData(202,1),new ItemParamData(998993,1))));
        assertFalse(inventory.payItem(998993,1));assertEquals(100,player.getMora());
    }
    @Test void duplicateCostsAreAggregatedBeforeDebit() {
        assertFalse(inventory.payItems(List.of(new ItemParamData(202, 60), new ItemParamData(202, 60))));
        assertEquals(100, player.getMora());
    }
    @Test void wrappingProductCannotCreditCurrency() {
        assertFalse(inventory.payItems(List.of(new ItemParamData(202, 2)), Integer.MAX_VALUE));
        assertEquals(100, player.getMora());
    }
    @Test void nonPositiveQuantityCannotCreditCurrency() {
        assertFalse(inventory.payItems(List.of(new ItemParamData(202, 20)), -1));
        assertFalse(inventory.payItem(202, -1));
        assertEquals(100, player.getMora());
    }
    @Test void exactAggregatedPaymentSucceeds() {
        assertTrue(inventory.payItems(new ItemParamData[]{new ItemParamData(202, 40), new ItemParamData(202, 60)}));
        assertEquals(0, player.getMora());
    }
    @Test void effectsAndMixedCapacityFailureRejectBeforeAnyInsertion() {
        var ordinary = new Gson().fromJson("{\"id\":998991,\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":10}",ItemData.class);
        var effect = new Gson().fromJson("{\"id\":998992,\"itemType\":\"ITEM_MATERIAL\",\"useOnGain\":true}",ItemData.class);
        assertFalse(inventory.tryAddItems(List.of(new GameItem(ordinary,1),new GameItem(effect,1)),null));
        assertTrue(inventory.getItems().isEmpty());
        inventory.createInventoryTab(ItemType.ITEM_MATERIAL,new MaterialInventoryTab(1));
        inventory.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(new GameItem(ordinary,9));
        assertTrue(inventory.canAddItems(List.of(new GameItem(ordinary,1))));
        assertFalse(inventory.canAddItems(List.of(new GameItem(ordinary,1),new GameItem(ordinary,1))));
        assertFalse(inventory.tryAddItems(List.of(new GameItem(ordinary,2)),null));
        assertEquals(9,inventory.getInventoryTab(ItemType.ITEM_MATERIAL).getItemById(998991).getCount());
    }
    @Test void overridableSingleItemCancellationCannotCausePartialBatchThenFalse() {
        var ordinary = new Gson().fromJson("{\"id\":998991,\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":10}",ItemData.class);
        var mora = new Gson().fromJson("{\"id\":202,\"itemType\":\"ITEM_VIRTUAL\"}",ItemData.class);
        var batch = new Inventory(player) {
            @Override public boolean addItem(GameItem item, emu.grasscutter.game.props.ActionReason reason) {
                fail("Ordinary batch must not enter single-item cancellation path");return false;
            }
        };
        var stack = new GameItem(ordinary,9) { @Override public void save() {} };
        batch.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(stack);
        assertTrue(batch.tryAddItems(List.of(new GameItem(ordinary,1),new GameItem(mora,1)),null));
        assertEquals(10,stack.getCount());assertEquals(101,player.getMora());
    }
    @Test void cascadingVirtualEffectsAndUnknownParamIdsRejectBeforeMutation() {
        var xp = new Gson().fromJson("{\"id\":102,\"itemType\":\"ITEM_VIRTUAL\"}",ItemData.class);
        assertFalse(inventory.tryAddItems(List.of(new GameItem(xp,1)),null));
        assertFalse(inventory.canAddItemParamDatas(List.of(new ItemParamData(999999999,1))));
        assertFalse(inventory.tryAddItemParamDatas(List.of(new ItemParamData(999999999,1)),null));
        assertTrue(inventory.getItems().isEmpty());
    }
    @Test void virtualOverflowRejectsBeforeMutation() {
        var mora = new Gson().fromJson("{\"id\":202,\"itemType\":\"ITEM_VIRTUAL\"}",ItemData.class);
        player.setMora(Integer.MAX_VALUE);
        assertFalse(inventory.tryAddItems(List.of(new GameItem(mora,1)),null));
        assertEquals(Integer.MAX_VALUE,player.getMora());
    }
    @Test void duplicateVirtualGrantsAreAggregatedForBounds() {
        var mora = new Gson().fromJson("{\"id\":202,\"itemType\":\"ITEM_VIRTUAL\"}",ItemData.class);
        player.setMora(Integer.MAX_VALUE-1);
        assertFalse(inventory.tryAddItems(List.of(new GameItem(mora,1),new GameItem(mora,1)),null));
        assertEquals(Integer.MAX_VALUE-1,player.getMora());
    }
    static class TestPlayer extends Player {
        @Override public void sendPacket(BasePacket p) {}
    }
}
