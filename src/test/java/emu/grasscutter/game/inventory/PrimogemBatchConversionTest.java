package emu.grasscutter.game.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.PlayerCodex;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.net.packet.BasePacket;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
final class PrimogemBatchConversionTest {
    private TestPlayer player;
    private Inventory inventory;
    private ItemData primogems;
    private ItemData output;

    @BeforeEach
    void setup() throws Exception {
        Field unsafe = Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        player = (TestPlayer) ((Unsafe) unsafe.get(null)).allocateInstance(TestPlayer.class);
        player.values = new HashMap<>();
        set("properties", player.values);
        inventory = new Inventory(player) {
            @Override public boolean addItem(int id, int count, ActionReason reason) {
                assertEquals(223, id);
                return super.addItem(new GameItem(GameData.getItemDataMap().get(id), count) {
                    @Override public void save() {}
                }, reason);
            }
        };
        set("inventory", inventory);
        set("codex", new PlayerCodex(player));
        inventory.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(1));
        primogems = data(201, "ITEM_VIRTUAL");
        output = data(998987, "ITEM_MATERIAL");
        GameData.getItemDataMap().put(output.getId(), output);
        GameData.getItemDataMap().put(223, data(223, "ITEM_MATERIAL"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void conversionCannotTakeTheValidatedOutputSlot(boolean currencyFirst) {
        GameItem currency = new GameItem(primogems, 160);
        GameItem material = new GameItem(output, 1) {
            @Override public void save() {}
        };
        var batch = currencyFirst ? List.of(currency, material) : List.of(material, currency);
        assertTrue(inventory.tryAddItems(batch, null));
        assertEquals(1, inventory.getItemById(output.getId()).getCount());
        assertNull(inventory.getItemById(223));
        assertEquals(160, player.getPrimogems(), "Keep unconverted currency when no fate slot fits");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void conversionStillUsesExistingFateStackHeadroom(boolean currencyFirst) {
        var fateData = GameData.getItemDataMap().get(223);
        var existing = new GameItem(fateData, 1) { @Override public void save() {} };
        existing.setOwner(player);
        inventory.getItems().put(existing.getGuid(), existing);
        inventory.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(existing);
        var currency = new GameItem(primogems, 160);
        var fate = new GameItem(fateData, 1);
        assertTrue(inventory.tryAddItems(currencyFirst ? List.of(currency, fate) : List.of(fate, currency), null));
        assertEquals(3, existing.getCount());
        assertEquals(0, player.getPrimogems());
    }

    @ParameterizedTest
    @CsvSource({"9999,160,false", "9999,160,true", "9998,320,false", "9998,320,true"})
    void conversionRetainsAllCurrencyWhenExistingFateHeadroomIsInsufficient(
            int existingCount, int currency, boolean batch) {
        var existing = seedFates(existingCount);
        if (batch) assertTrue(inventory.tryAddItems(List.of(new GameItem(primogems, currency)), null));
        else assertTrue(player.setPrimogems(currency));
        assertEquals(existingCount, existing.getCount());
        assertEquals(currency, player.getPrimogems());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oversizedNewConversionCannotBeConstructorClamped(boolean batch) {
        int currency = 160 * 10000;
        if (batch) assertTrue(inventory.tryAddItems(List.of(new GameItem(primogems, currency)), null));
        else assertTrue(player.setPrimogems(currency));
        assertNull(inventory.getItemById(223));
        assertEquals(currency, player.getPrimogems());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exactExistingFateHeadroomStillConverts(boolean batch) {
        var existing = seedFates(9998);
        if (batch) assertTrue(inventory.tryAddItems(List.of(new GameItem(primogems, 160)), null));
        else assertTrue(player.setPrimogems(160));
        assertEquals(9999, existing.getCount());
        assertEquals(0, player.getPrimogems());
    }

    private GameItem seedFates(int count) {
        var existing = new GameItem(GameData.getItemDataMap().get(223), count) {
            @Override public void save() {}
        };
        existing.setOwner(player);
        inventory.getItems().put(existing.getGuid(), existing);
        inventory.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(existing);
        return existing;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exchangeReservesTheSlotFreedByItsIngredientBeforeConverting(boolean currencyFirst) {
        var inputData = data(998986, "ITEM_MATERIAL");
        GameData.getItemDataMap().put(inputData.getId(), inputData);
        var ingredient = new GameItem(inputData, 1) {
            @Override public void save() {}
        };
        ingredient.setOwner(player);
        inventory.getItems().put(ingredient.getGuid(), ingredient);
        inventory.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(ingredient);
        var material = new GameItem(output, 1) { @Override public void save() {} };
        var currency = new GameItem(primogems, 160);
        assertTrue(inventory.tryPayAndAddItems(List.of(new ItemParamData(inputData.getId(), 1)), 1,
                currencyFirst ? List.of(currency, material) : List.of(material, currency), null));
        assertNull(inventory.getItemById(inputData.getId()));
        assertEquals(1, inventory.getItemById(output.getId()).getCount());
        assertNull(inventory.getItemById(223));
        assertEquals(160, player.getPrimogems());
    }

    @Test
    void legacyConversionOutsideBatchRemainsImmediate() {
        assertTrue(player.setPrimogems(160));
        assertEquals(1, inventory.getItemById(223).getCount());
        assertEquals(0, player.getPrimogems());
    }

    @Test
    void failedBatchClearsDeferralWithoutConvertingItsPartialGrant() {
        inventory.createInventoryTab(ItemType.ITEM_MATERIAL, new MaterialInventoryTab(2));
        player.failStorePacket = true;
        var material = new GameItem(output, 1) { @Override public void save() {} };
        assertThrows(IllegalStateException.class, () -> inventory.tryAddItems(
                List.of(new GameItem(primogems, 160), material), null));
        assertEquals(160, player.getPrimogems());
        assertNull(inventory.getItemById(223));
        player.failStorePacket = false;
        assertTrue(player.setPrimogems(320));
        assertEquals(2, inventory.getItemById(223).getCount());
        assertEquals(0, player.getPrimogems());
    }

    private ItemData data(int id, String type) {
        return new Gson().fromJson("{\"id\":" + id + ",\"itemType\":\"" + type
                + "\",\"materialType\":\"MATERIAL_EXCHANGE\",\"stackLimit\":9999}", ItemData.class);
    }

    private void set(String name, Object value) throws Exception {
        Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    private static class TestPlayer extends Player {
        private Map<Integer, Integer> values;
        private boolean failStorePacket;
        @Override public int getProperty(PlayerProperty property) {
            return values.getOrDefault(property.getId(), 0);
        }
        @Override public boolean setProperty(PlayerProperty property, int value) {
            values.put(property.getId(), value);
            return true;
        }
        @Override public void sendPacket(BasePacket packet) {
            if (failStorePacket && packet instanceof emu.grasscutter.server.packet.send.PacketStoreItemChangeNotify)
                throw new IllegalStateException("Injected notification failure");
        }
    }
}
