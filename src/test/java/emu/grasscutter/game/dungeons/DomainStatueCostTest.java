package emu.grasscutter.game.dungeons;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;

import emu.grasscutter.data.excels.dungeon.DungeonData;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.Player;

import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

import java.lang.reflect.*;

class DomainStatueCostTest {
    @Test
    void customMaterialCostIsActuallyDebited() throws Exception {
        var u = Unsafe.class.getDeclaredField("theUnsafe");
        u.setAccessible(true);
        var p = (FakePlayer) ((Unsafe) u.get(null)).allocateInstance(FakePlayer.class);
        p.inv = (FakeInventory) ((Unsafe) u.get(null)).allocateInstance(FakeInventory.class);
        var row =
                new Gson()
                        .fromJson(
                                "{\"statueCostID\":991555,\"statueCostCount\":7}",
                                DungeonData.class);
        var pay =
                DomainStatueDropService.class.getDeclaredMethod(
                        "payCost",
                        Player.class,
                        DungeonData.class,
                        DomainStatueClaimHelper.ClaimMode.class);
        pay.setAccessible(true);
        assertTrue((Boolean) pay.invoke(null, p, row, DomainStatueClaimHelper.ClaimMode.NORMAL_1X));
        assertEquals(991555, p.inv.id);
        assertEquals(7, p.inv.count);
        assertTrue((Boolean) pay.invoke(null, p, row, DomainStatueClaimHelper.ClaimMode.NORMAL_2X));
        assertEquals(14, p.inv.count);
    }

    static class FakePlayer extends Player {
        FakeInventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static class FakeInventory extends Inventory {
        int id, count;

        FakeInventory() {
            super(null);
        }

        @Override
        public boolean payItem(int id, int count) {
            this.id = id;
            this.count = count;
            return true;
        }
    }
}
