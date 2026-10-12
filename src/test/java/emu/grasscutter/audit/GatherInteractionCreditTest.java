package emu.grasscutter.audit;

import static emu.grasscutter.audit.AuditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ActionReason;
import emu.grasscutter.game.quest.QuestManager;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.GadgetInteractReqOuterClass.GadgetInteractReq;
import org.junit.jupiter.api.Test;

class GatherInteractionCreditTest {
    @Test void itemPickupCreditsOnlyAfterAnAcceptedGrant() {
        var player = player();
        var item = allocate(Pickup.class); item.scene = allocate(RemovalScene.class);
        item.data = new Gson().fromJson("{\"id\":987321,\"gadgetId\":456,\"itemType\":\"ITEM_MATERIAL\"}", ItemData.class);
        item.onInteract(player, GadgetInteractReq.getDefaultInstance());
        assertEquals(0, player.quests.credits);
        player.inventory.accept = true;
        item.onInteract(player, GadgetInteractReq.getDefaultInstance());
        assertEquals(1, player.quests.credits); assertEquals(456, player.quests.gadgetId);
    }
    @Test void animalGatherCreditsOnlyAfterAnAcceptedGrantAndNeverOnReplay() {
        var player = player();
        var monster = allocate(Animal.class); monster.scene = allocate(RemovalScene.class);
        monster.data = new Gson().fromJson("{\"id\":987321}", emu.grasscutter.data.excels.monster.MonsterData.class);
        var gather = new Gson().fromJson("{\"id\":987321,\"gatherItem\":{\"id\":987322,\"count\":1}}", EnvAnimalGatherConfigData.class);
        var old = GameData.getEnvAnimalGatherConfigDataMap().put(987321, gather);
        try {
            var req = GadgetInteractReq.newBuilder().setGadgetId(789).build();
            monster.onInteract(player, req); assertEquals(0, player.quests.credits);
            player.inventory.accept = true;
            monster.onInteract(player, req); monster.onInteract(player, req);
            assertEquals(1, player.quests.credits); assertEquals(789, player.quests.gadgetId);
        } finally {
            if (old == null) GameData.getEnvAnimalGatherConfigDataMap().remove(987321);
            else GameData.getEnvAnimalGatherConfigDataMap().put(987321, old);
        }
    }
    static P player() {
        var p = allocate(P.class); p.quests = new Q(); p.inventory = new I(p); return p;
    }
    static class P extends Player {
        Q quests; I inventory;
        @Override public Inventory getInventory() { return inventory; }
        @Override public void sendPacket(BasePacket packet) {}
    }
    static class Q { int credits, gadgetId; }
    static class I extends Inventory {
        boolean accept;
        I(Player p) { super(p); }
        @Override public boolean addItem(GameItem item, ActionReason reason) { return accept; }
        @Override public boolean addItem(emu.grasscutter.data.common.ItemParamData item, ActionReason reason) { return accept; }
    }
    static class Pickup extends EntityItem {
        Scene scene; ItemData data;
        Pickup() { super(null, null, null, null, 0); }
        @Override public boolean isShare() { return true; }
        @Override public ItemData getItemData() { return data; }
        @Override public int getCount() { return 1; }
        @Override protected void creditInteraction(Player p) {
            ((P)p).quests.credits++; ((P)p).quests.gadgetId = getGadgetId();
        }
        @Override public Scene getScene() { return scene; }
    }
    static class Animal extends EntityMonster {
        Scene scene; emu.grasscutter.data.excels.monster.MonsterData data;
        Animal() { super(null, null, null, null, 1); }
        @Override public emu.grasscutter.data.excels.monster.MonsterData getMonsterData() { return data; }
        @Override protected void creditInteraction(Player p, GadgetInteractReq req) {
            ((P)p).quests.credits++; ((P)p).quests.gadgetId = req.getGadgetId();
        }
        @Override public Scene getScene() { return scene; }
    }
    static class RemovalScene extends Scene {
        RemovalScene() { super(null, null); }
        @Override public synchronized void removeEntity(GameEntity entity) {}
        @Override public void killEntity(GameEntity entity) {}
        @Override public void broadcastPacket(BasePacket packet) {}
    }
}
