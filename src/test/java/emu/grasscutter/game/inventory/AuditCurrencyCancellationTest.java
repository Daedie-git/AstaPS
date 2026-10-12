package emu.grasscutter.game.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.game.managers.forging.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.PlayerProperty;
import emu.grasscutter.plugin.PluginManager;
import emu.grasscutter.server.event.*;
import emu.grasscutter.server.event.player.PlayerPropertyChangeEvent;
import emu.grasscutter.net.proto.ForgeStartReqOuterClass.ForgeStartReq;
import emu.grasscutter.net.proto.ForgeQueueManipulateReqOuterClass.ForgeQueueManipulateReq;
import emu.grasscutter.net.proto.ForgeQueueManipulateTypeOuterClass.ForgeQueueManipulateType;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;

@ExtendWith(ServerResourceFixture.class)
class AuditCurrencyCancellationTest {
    static final int ID = AuditInventoryCallersTest.ID;
    AuditInventoryCallersTest fixture;
    RealPlayer player;
    Inventory inv;
    Field pluginField;
    Object oldPlugins;
    ItemData oldMora;
    int events;
    java.util.function.Consumer<PlayerPropertyChangeEvent> policy = PlayerPropertyChangeEvent::cancel;

    @BeforeEach void realCurrencyListener() throws Exception {
        fixture = new AuditInventoryCallersTest(); fixture.setup();
        var f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        var unsafe = (Unsafe) f.get(null);
        var real = (RealPlayer) unsafe.allocateInstance(RealPlayer.class);
        real.forges = fixture.player.forges; real.compounds = fixture.player.compounds; real.sent = fixture.player.sent;
        real.points = fixture.player.points; real.recipes = fixture.player.recipes; real.combines = fixture.player.combines;
        initializeProperties(real);
        player = real; inv = new Inventory(real); real.inventory = inv;
        player.setMora(100);
        oldMora = GameData.getItemDataMap().put(202, new Gson().fromJson("{\"id\":202,\"itemType\":\"ITEM_VIRTUAL\"}", ItemData.class));
        pluginField = Grasscutter.class.getDeclaredField("pluginManager"); pluginField.setAccessible(true);
        oldPlugins = pluginField.get(null);
        var manager = (PluginManager) unsafe.allocateInstance(PluginManager.class);
        var handlers = PluginManager.class.getDeclaredField("handlers"); handlers.setAccessible(true);
        handlers.set(manager, new LinkedHashMap<>());
        pluginField.set(null, manager);
        manager.registerListener(new EventHandler<>(PlayerPropertyChangeEvent.class)
                .priority(HandlerPriority.NORMAL).listener(event -> {
                    if (event.getProperty() == PlayerProperty.PROP_PLAYER_SCOIN) {
                        events++; policy.accept(event);
                    }
                }));
    }

    static void initializeProperties(Player player) throws Exception {
        var field = Player.class.getDeclaredField("properties"); field.setAccessible(true);
        Map<Integer, Integer> properties = new HashMap<>();
        for (var prop : PlayerProperty.values()) properties.put(prop.getId(), 0);
        field.set(player, properties);
    }

    @AfterEach void restoreListener() throws Exception {
        pluginField.set(null, oldPlugins);
        if (oldMora == null) GameData.getItemDataMap().remove(202);
        else GameData.getItemDataMap().put(202, oldMora);
        fixture.restore();
    }

    @Test void realSetterCancellationRejectsSingleGrantAndPayment() {
        assertFalse(player.setMora(101));
        assertFalse(inv.addItem(new GameItem(202, 1)));
        assertFalse(inv.payItem(202, 1));
        assertEquals(100, player.getMora()); assertEquals(3, events);
    }

    @Test void cancelledMixedGrantRejectsBeforePhysicalInsertion() {
        var stack = new AuditInventoryCallersTest.MemoryItem(GameData.getItemDataMap().get(ID), 9);
        inv.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(stack);
        assertFalse(inv.tryAddItems(List.of(new GameItem(ID, 1), new GameItem(202, 1)), null));
        assertEquals(9, stack.getCount()); assertEquals(100, player.getMora()); assertEquals(1, events);
    }

    @Test void cancelledMixedPaymentRejectsBeforePhysicalDebit() {
        var stack = new AuditInventoryCallersTest.MemoryItem(GameData.getItemDataMap().get(ID), 2);
        inv.getItems().put(stack.getGuid(), stack);
        inv.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(stack);
        assertFalse(inv.payItems(List.of(new ItemParamData(ID, 1), new ItemParamData(202, 1))));
        assertEquals(2, stack.getCount()); assertEquals(100, player.getMora()); assertEquals(1, events);
    }

    @Test void cancelledExchangeRejectsBeforeInputConsumptionAndOutputGrant() {
        var stack = new AuditInventoryCallersTest.MemoryItem(GameData.getItemDataMap().get(ID), 1);
        inv.getItems().put(stack.getGuid(), stack);
        inv.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(stack);
        var costs = List.of(new ItemParamData(ID, 1), new ItemParamData(202, 1));
        assertTrue(inv.canPayAndAddItems(costs, 1, List.of(new GameItem(ID, 1))));
        assertEquals(0, events);
        assertFalse(inv.tryPayAndAddItems(costs, 1, List.of(new GameItem(ID, 1)), null));
        assertEquals(1, stack.getCount()); assertSame(stack, inv.getItemByGuid(stack.getGuid()));
        assertEquals(100, player.getMora()); assertEquals(1, events);
    }

    @Test void cancelledForgeCostCannotStartProduction() {
        new ForgingManager(player).handleForgeStartReq(ForgeStartReq.newBuilder().setForgeId(ID).setForgeCount(1).build());
        assertTrue(player.forges.isEmpty()); assertEquals(100, player.points);
        assertEquals(100, player.getMora()); assertEquals(1, events);
    }

    @Test void cancelledForgeRefundRetainsQueueAndPoints() throws Exception {
        var materials = GameData.getForgeDataMap().get(ID).getMaterialItems();
        materials.add(new ItemParamData(ID, 1));
        var stack = new AuditInventoryCallersTest.MemoryItem(GameData.getItemDataMap().get(ID), 9);
        inv.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(stack);
        var queue = new ActiveForgeData(); queue.setForgeId(ID); queue.setCount(1);
        queue.setStartTime(emu.grasscutter.utils.Utils.getCurrentSeconds()); queue.setForgeTime(1000);
        player.forges.add(queue);
        new ForgingManager(player).handleForgeQueueManipulateReq(ForgeQueueManipulateReq.newBuilder()
                .setForgeQueueId(1).setManipulateType(ForgeQueueManipulateType.ForgeQueueManipulateType_STOP_FORGE).build());
        assertEquals(1, player.forges.size()); assertEquals(100, player.points);
        assertEquals(9, stack.getCount());
        assertEquals(100, player.getMora()); assertEquals(1, events);
    }

    @Test void acceptedDuplicateCurrencyGrantResolvesEventOnce() {
        policy = event -> {};
        assertTrue(inv.tryAddItems(List.of(new GameItem(202, 1), new GameItem(202, 2)), null));
        assertEquals(103, player.getMora()); assertEquals(1, events);
    }

    @Test void alteredCurrencyAmountRejectsBeforeMutation() {
        policy = event -> event.setNewValue(event.getNewValue() + 1);
        assertFalse(inv.payItems(List.of(new ItemParamData(202, 1))));
        assertFalse(inv.tryAddItems(List.of(new GameItem(202, 1)), null));
        assertEquals(100, player.getMora()); assertEquals(2, events);
    }

    @Test void unexpectedFailureAfterCurrencyCommitThrowsInsteadOfRetryFalse() {
        policy = event -> {};
        var stack = new GameItem(GameData.getItemDataMap().get(ID), 9) {
            @Override public void save() { throw new IllegalStateException("persistence failure"); }
        };
        inv.getInventoryTab(ItemType.ITEM_MATERIAL).onAddItem(stack);
        assertThrows(IllegalStateException.class,
                () -> inv.tryAddItems(List.of(new GameItem(202, 1), new GameItem(ID, 1)), null));
        assertEquals(101, player.getMora()); assertEquals(10, stack.getCount());
    }

    static class RealPlayer extends AuditInventoryCallersTest.P {
        @Override public int getMora() { return getProperty(PlayerProperty.PROP_PLAYER_SCOIN); }
        @Override public boolean setMora(int value) { return setProperty(PlayerProperty.PROP_PLAYER_SCOIN, value); }
    }
}
