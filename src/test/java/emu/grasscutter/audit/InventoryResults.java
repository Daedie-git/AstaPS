package emu.grasscutter.audit;

import emu.grasscutter.game.inventory.GameItem;
import emu.grasscutter.game.inventory.InventoryAddResult;
import java.util.Collection;

public final class InventoryResults {
    private InventoryResults() {}
    public static InventoryAddResult accepted(Collection<GameItem> items) {
        return new InventoryAddResult(items.stream().map(item -> new InventoryAddResult.Entry(
                item.getItemId(), item.getCount(), item.getCount(), InventoryAddResult.Status.ADDED)).toList());
    }
}
