package emu.grasscutter.game;

import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.game.CombatAuditTest.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.ItemData;
import emu.grasscutter.data.excels.monster.MonsterData;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.managers.energy.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.*;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.proto.GadgetInteractReqOuterClass.GadgetInteractReq;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ServerResourceFixture.class)
class CombatEnergyDropTest {
    @Test void realMonsterDropCountIsConstantAndSharedPickupConsumesOnePool() throws Exception {
        int dropId = 990001;
        int ballId = 990002;
        var field = EnergyManager.class.getDeclaredField("energyDropData");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var drops = (Int2ObjectMap<List<EnergyDropInfo>>) field.get(null);
        var oldDrops = drops.get(dropId);
        var oldItem = GameData.getItemDataMap().get(ballId);
        var item = new ItemData();
        set(item, ItemData.class, "id", ballId);
        set(item, ItemData.class, "itemType", ItemType.ITEM_VIRTUAL);
        var info = new EnergyDropInfo();
        set(info, EnergyDropInfo.class, "ballId", ballId);
        set(info, EnergyDropInfo.class, "count", 3);
        drops.put(dropId, List.of(info));
        GameData.getItemDataMap().put(ballId, item);
        try {
            for (int count : new int[]{1, 2, 4}) {
                var f = fixture();
                var scene = allocate(DropScene.class);
                scene.packets = f.scene.packets;
                scene.entities = f.scene.entities;
                scene.players = f.scene.players;
                scene.world = f.scene.world;
                set(scene.world, emu.grasscutter.game.world.World.class, "nextEntityId", new AtomicInteger(100));
                f.scene = scene;
                f.player.scene = scene;
                var players = new ArrayList<TestPlayer>();
                players.add(f.player);
                for (int i = 1; i < count; i++) {
                    var player = allocate(TestPlayer.class);
                    player.scene = scene;
                    players.add(player);
                    scene.players.add(player);
                }
                for (int i = 0; i < count; i++) {
                    var player = players.get(i);
                    player.setUid(i + 1);
                    set(player, Player.class, "energyManager", new EnergyManager(player));
                    set(player, Player.class, "questManager", new emu.grasscutter.game.quest.QuestManager(player));
                    var inventory = allocate(RecordingInventory.class);
                    set(player, Player.class, "inventory", inventory);
                }
                var monster = allocate(TestMonster.class);
                monster.props = new Int2FloatOpenHashMap();
                set(monster, GameEntity.class, "scene", scene);
                set(monster, EntityMonster.class, "position", new Position(1, 2, 3));
                var data = new MonsterData();
                set(data, MonsterData.class, "type", MonsterType.MONSTER_ORDINARY);
                set(data, MonsterData.class, "hpDrops", List.of());
                set(data, MonsterData.class, "killDropId", dropId);
                set(monster, EntityMonster.class, "monsterData", data);
                monster.setFightProperty(FightProperty.FIGHT_PROP_MAX_HP, 100);
                monster.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP, 100);
                monster.damage(100, 0, ElementType.None);
                var balls = scene.entities.values().stream().filter(EntityItem.class::isInstance).map(EntityItem.class::cast).toList();
                assertEquals(1, balls.size());
                var ball = balls.get(0);
                assertEquals(3, ball.getCount());
                assertTrue(ball.isShare());
                assertEquals(1, (int) (ball.getGuid() >> 32), "stable lowest UID seeds the shared pool");
                var collector = players.get(count - 1);
                var inventory = (RecordingInventory) collector.getInventory();
                ball.onInteract(collector, GadgetInteractReq.getDefaultInstance());
                assertSame(ball, scene.getEntityById(ball.getId()), "failed delivery retains the pool");
                inventory.accept = true;
                ball.onInteract(collector, GadgetInteractReq.getDefaultInstance());
                assertEquals(3, inventory.received);
                assertNull(scene.getEntityById(ball.getId()), "successful collector removes the shared pool for everybody");
                for (var player : players) {
                    if (player != collector) assertEquals(0, ((RecordingInventory) player.getInventory()).received);
                }
                monster.damage(1, 0, ElementType.None);
                assertEquals(0, scene.entities.values().stream().filter(EntityItem.class::isInstance).count(), "already-dead damage does not respawn drops");
            }
        } finally {
            if (oldDrops == null) drops.remove(dropId); else drops.put(dropId, oldDrops);
            if (oldItem == null) GameData.getItemDataMap().remove(ballId); else GameData.getItemDataMap().put(ballId, oldItem);
        }
    }

    private static class DropScene extends TestScene {
        @Override public void addEntity(GameEntity entity) { entities.put(entity.getId(), entity); }
        @Override public void removeEntity(GameEntity entity) { entities.remove(entity.getId()); }
    }
    private static class RecordingInventory extends Inventory {
        boolean accept;
        int received;
        RecordingInventory() { super(null); }
        @Override public boolean addItem(GameItem item, ActionReason reason) {
            if (!accept) return false;
            received += item.getCount();
            return true;
        }
    }
}
