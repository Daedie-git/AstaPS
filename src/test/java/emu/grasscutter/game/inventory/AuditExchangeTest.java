package emu.grasscutter.game.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import java.util.List;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.game.combine.CombineManger;
import emu.grasscutter.game.managers.cooking.CookingManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.PlayerCodex;
import emu.grasscutter.net.proto.PlayerCookReqOuterClass.PlayerCookReq;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
class AuditExchangeTest {
    static final int ID = AuditInventoryCallersTest.ID;
    static final int INPUT = ID + 20;
    AuditInventoryCallersTest fixture;
    ItemData oldInput;
    CombineData oldCombine;
    CookRecipeData oldRecipe;

    @BeforeEach void setup() throws Exception {
        fixture = new AuditInventoryCallersTest(); fixture.setup();
        initializeCodex(fixture.player);
        fixture.player.setMora(100);
        fixture.inv.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(1));
        oldInput = GameData.getItemDataMap().put(INPUT, new Gson().fromJson(
                "{\"id\":" + INPUT + ",\"itemType\":\"ITEM_MATERIAL\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":10}", ItemData.class));
        oldCombine = GameData.getCombineDataMap().put(ID, new Gson().fromJson(
                "{\"combineId\":" + ID + ",\"resultItemId\":" + ID + ",\"resultItemCount\":1,\"scoinCost\":1,\"materialItems\":[{\"id\":" + INPUT + ",\"count\":3}]}", CombineData.class));
        oldRecipe = GameData.getCookRecipeDataMap().put(ID, new Gson().fromJson(
                "{\"id\":" + ID + ",\"maxProficiency\":10,\"inputVec\":[{\"id\":" + INPUT + ",\"count\":3},{\"id\":202,\"count\":1}],\"qualityOutputVec\":[{\"id\":" + ID + ",\"count\":1}]}", CookRecipeData.class));
    }

    static void initializeCodex(Player player) throws Exception {
        var field = Player.class.getDeclaredField("codex"); field.setAccessible(true);
        field.set(player, new PlayerCodex(player));
    }

    static GameItem seed(Inventory inventory, int id, int count) {
        var item = new AuditInventoryCallersTest.MemoryItem(GameData.getItemDataMap().get(id), count);
        inventory.getItems().put(item.getGuid(), item);
        inventory.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(item);
        return item;
    }

    @AfterEach void restore() {
        if (oldInput == null) GameData.getItemDataMap().remove(INPUT); else GameData.getItemDataMap().put(INPUT, oldInput);
        if (oldCombine == null) GameData.getCombineDataMap().remove(ID); else GameData.getCombineDataMap().put(ID, oldCombine);
        if (oldRecipe == null) GameData.getCookRecipeDataMap().remove(ID); else GameData.getCookRecipeDataMap().put(ID, oldRecipe);
        fixture.restore();
    }

    @Test void entireDuplicateCostBatchIsCheckedWithoutMutation() {
        var input = seed(fixture.inv, INPUT, 3);
        var costs = List.of(new ItemParamData(INPUT, 2), new ItemParamData(INPUT, 2), new ItemParamData(202, 1));
        var grants = List.of(new GameItem(ID, 1));
        assertFalse(fixture.inv.canPayAndAddItems(costs, 1, grants));
        assertFalse(fixture.inv.tryPayAndAddItems(costs, 1, grants, null));
        assertEquals(3, input.getCount()); assertEquals(100, fixture.player.getMora());
        assertEquals(0, fixture.inv.getItemCountById(ID));
    }

    @Test void laterUnaffordableCurrencyCostDoesNotConsumeExhaustibleInput() {
        var input = seed(fixture.inv, INPUT, 3);
        var costs = List.of(new ItemParamData(INPUT, 3), new ItemParamData(202, 101));
        assertFalse(fixture.inv.tryPayAndAddItems(costs, 1, List.of(new GameItem(ID, 1)), null));
        assertEquals(3, input.getCount()); assertEquals(100, fixture.player.getMora());
        assertEquals(0, fixture.inv.getItemCountById(ID));
    }

    @Test void postDebitStackHeadroomIncludesDuplicateGrants() {
        var input = seed(fixture.inv, INPUT, 10);
        var costs = List.of(new ItemParamData(INPUT, 3));
        var grants = List.of(new GameItem(INPUT, 1), new GameItem(INPUT, 2));
        assertTrue(fixture.inv.canPayAndAddItems(costs, 1, grants));
        assertEquals(10, input.getCount());
        assertTrue(fixture.inv.tryPayAndAddItems(costs, 1, grants, null));
        assertEquals(10, input.getCount()); assertEquals(1, fixture.inv.getInventoryTab(ItemType.ITEM_MATERIAL).getSize());
        assertFalse(fixture.inv.tryPayAndAddItems(costs, 1, List.of(new GameItem(INPUT, 4)), null));
        assertEquals(10, input.getCount());
    }

    @Test void freedSlotDoesNotHideDuplicateGrantOverflowOrPaymentOverflow() {
        var input = seed(fixture.inv, INPUT, 3);
        var costs = List.of(new ItemParamData(INPUT, 3));
        assertFalse(fixture.inv.tryPayAndAddItems(costs, 1,
                List.of(new GameItem(ID, 6), new GameItem(ID, 5)), null));
        assertFalse(fixture.inv.tryPayAndAddItems(costs, Integer.MAX_VALUE, List.of(new GameItem(ID, 1)), null));
        assertEquals(3, input.getCount()); assertEquals(0, fixture.inv.getItemCountById(ID));
    }

    @Test void postDebitCurrencyHeadroomIsValidatedWithoutChangingBalance() {
        fixture.player.setMora(Integer.MAX_VALUE);
        var mora = new Gson().fromJson("{\"id\":202,\"itemType\":\"ITEM_VIRTUAL\"}", ItemData.class);
        var costs = List.of(new ItemParamData(202, 1));
        assertTrue(fixture.inv.canPayAndAddItems(costs, 1, List.of(new GameItem(mora, 1))));
        assertFalse(fixture.inv.canPayAndAddItems(costs, 1, List.of(new GameItem(mora, 2))));
        assertFalse(fixture.inv.tryPayAndAddItems(costs, 1, List.of(new GameItem(mora, 2)), null));
        assertEquals(Integer.MAX_VALUE, fixture.player.getMora());
        assertTrue(fixture.inv.tryPayAndAddItems(costs, 1, List.of(new GameItem(mora, 1)), null));
        assertEquals(Integer.MAX_VALUE, fixture.player.getMora());
    }

    @Test void combineExhaustedIngredientFreesOutputSlotExactlyOnce() {
        seed(fixture.inv, INPUT, 3);
        var manager = new CombineManger(null);
        assertNotNull(manager.combineItem(fixture.player, ID, 1));
        assertEquals(0, fixture.inv.getItemCountById(INPUT));
        assertEquals(1, fixture.inv.getItemCountById(ID)); assertEquals(99, fixture.player.getMora());
        assertNull(manager.combineItem(fixture.player, ID, 1));
        assertEquals(1, fixture.inv.getItemCountById(ID)); assertEquals(99, fixture.player.getMora());
    }

    @Test void combineLeftoverIngredientRejectsUnchanged() {
        var input = seed(fixture.inv, INPUT, 4);
        assertNull(new CombineManger(null).combineItem(fixture.player, ID, 1));
        assertEquals(4, input.getCount()); assertEquals(0, fixture.inv.getItemCountById(ID));
        assertEquals(100, fixture.player.getMora());
    }

    @Test void cookingExhaustedIngredientFreesOutputSlotExactlyOnce() {
        seed(fixture.inv, INPUT, 3);
        var manager = new CookingManager(fixture.player);
        var req = PlayerCookReq.newBuilder().setRecipeId(ID).setCookCount(1).build();
        manager.handlePlayerCookReq(req);
        assertEquals(0, fixture.inv.getItemCountById(INPUT)); assertEquals(1, fixture.inv.getItemCountById(ID));
        assertEquals(99, fixture.player.getMora()); assertEquals(1, fixture.player.recipes.get(ID));
        manager.handlePlayerCookReq(req);
        assertEquals(1, fixture.inv.getItemCountById(ID)); assertEquals(99, fixture.player.getMora());
        assertEquals(1, fixture.player.recipes.get(ID));
    }

    @Test void cookingLeftoverIngredientRejectsUnchanged() {
        var input = seed(fixture.inv, INPUT, 4);
        new CookingManager(fixture.player).handlePlayerCookReq(PlayerCookReq.newBuilder().setRecipeId(ID).setCookCount(1).build());
        assertEquals(4, input.getCount()); assertEquals(0, fixture.inv.getItemCountById(ID));
        assertEquals(100, fixture.player.getMora()); assertTrue(fixture.player.recipes.isEmpty());
    }
}
