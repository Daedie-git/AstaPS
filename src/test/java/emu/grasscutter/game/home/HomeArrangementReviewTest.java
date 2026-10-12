package emu.grasscutter.game.home;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.HomeworldDefaultSaveData;
import emu.grasscutter.data.excels.*;
import emu.grasscutter.game.inventory.Inventory;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.HomeAnimalDataOuterClass.HomeAnimalData;
import emu.grasscutter.net.proto.HomeBlockArrangementInfoOuterClass.HomeBlockArrangementInfo;
import emu.grasscutter.net.proto.HomeFurnitureDataOuterClass.HomeFurnitureData;
import emu.grasscutter.net.proto.HomeSceneArrangementInfoOuterClass.HomeSceneArrangementInfo;
import emu.grasscutter.net.proto.HomeUpdateArrangementInfoReqOuterClass.HomeUpdateArrangementInfoReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.recv.HandlerHomeUpdateArrangementInfoReq;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.misc.Unsafe;
import java.util.*;

@ExtendWith(ServerResourceFixture.class)
class HomeArrangementReviewTest {
    final List<Runnable> restore = new ArrayList<>();
    P player; HomeSceneItem outdoor, indoor;
    @BeforeEach void setup() throws Exception {
        player = allocate(P.class); player.inv = allocate(I.class); player.inv.counts = new HashMap<>();
        player.home = allocate(GameHome.class);
        outdoor = scene(2001); indoor = scene(2201);
        outdoor.setMainHouse(HomeFurnitureItem.of().furnitureId(991871).guid(0).spawnPos(new Position()).spawnRot(new Position()).build());
        player.home.setSceneMap(new java.util.concurrent.ConcurrentHashMap<>(Map.of(2001, outdoor)));
        player.home.setMainHouseMap(new java.util.concurrent.ConcurrentHashMap<>(Map.of(2001, indoor)));
        item(991871, 2201); item(991872, 2202);
        put(GameData.getHomeworldDefaultSaveData(), 2001, new Gson().fromJson("{\"mainhouse\":{\"id\":991871}}", HomeworldDefaultSaveData.class));
        put(GameData.getHomeworldDefaultSaveData(), 2201, new HomeworldDefaultSaveData());
        put(GameData.getHomeworldDefaultSaveData(), 2202, new HomeworldDefaultSaveData());
        put(GameData.getItemDataMap(), 991873, new Gson().fromJson("{\"id\":991873,\"itemType\":\"ITEM_FURNITURE\"}", ItemData.class));
        put(GameData.getHomeWorldAnimalDataMap(), 991873, new Gson().fromJson("{\"furnitureID\":991873,\"monsterID\":1}", HomeWorldAnimalData.class));
    }
    void item(int id, int room) {
        put(GameData.getItemDataMap(), id, new Gson().fromJson("{\"id\":"+id+",\"itemType\":\"ITEM_FURNITURE\",\"specialFurnitureType\":\"Apartment\",\"roomSceneId\":"+room+"}", ItemData.class));
    }
    <T> void put(Map<Integer,T> map, int id, T value) {
        var old = map.put(id,value); restore.add(() -> { if(old==null) map.remove(id); else map.put(id,old); });
    }
    @AfterEach void cleanup() { Collections.reverse(restore); restore.forEach(Runnable::run); }
    static HomeSceneItem scene(int id) {
        var block = HomeBlockItem.of().blockId(1).unlocked(true).deployFurnitureList(List.of())
                .persistentFurnitureList(List.of()).deployAnimalList(List.of()).deployNPCList(List.of()).suiteList(List.of()).build();
        return HomeSceneItem.of().sceneId(id).blockItems(new HashMap<>(Map.of(1, block)))
                .bornPos(new Position()).bornRot(new Position()).djinnPos(new Position()).build();
    }
    HomeSceneArrangementInfo house(int id, int guid) {
        return HomeSceneArrangementInfo.newBuilder().setMainHouse(HomeFurnitureData.newBuilder().setFurnitureId(id).setGuid(guid)).build();
    }
    void handle(HomeSceneArrangementInfo candidate) throws Exception {
        new HandlerHomeUpdateArrangementInfoReq().handle(new Session(player), null,
                HomeUpdateArrangementInfoReq.newBuilder().setSceneArrangementInfo(candidate).build().toByteArray());
    }
    @Test void unknownNonHouseAndUnownedReplacementRejectBeforeIndoorReset() throws Exception {
        for (var candidate : List.of(house(999999999, 1), house(991873, 1), house(991872, 1))) {
            handle(candidate);
            assertEquals(991871, outdoor.getMainHouse().getFurnitureId());
            assertSame(indoor, player.home.getMainHouseMap().get(2001));
        }
    }
    @Test void ownedReplacementAndPreservedDefaultIdentityRemainLegal() {
        assertTrue(outdoor.validateArrangement(house(991871, 0), player));
        assertFalse(outdoor.validateArrangement(house(991871, 77), player));
        player.inv.counts.put(991872, 1);
        outdoor.update(house(991872, 1), player);
        assertEquals(991872, outdoor.getMainHouse().getFurnitureId());
        assertEquals(2202, outdoor.getRoomSceneId());
    }
    @Test void animalsRequireKnownOwnedCountsIncludingCounterpart() {
        var candidate = HomeSceneArrangementInfo.newBuilder().addBlockArrangementInfoList(HomeBlockArrangementInfo.newBuilder()
                .setBlockId(1).addDeployAnimalList(HomeAnimalData.newBuilder().setFurnitureId(991873))).build();
        outdoor.update(candidate, player);
        assertTrue(outdoor.getBlockItems().get(1).getDeployAnimalList().isEmpty());
        player.inv.counts.put(991873, 1);
        indoor.getBlockItems().get(1).setDeployAnimalList(List.of(HomeAnimalItem.of().furnitureId(991873).build()));
        outdoor.update(candidate, player);
        assertTrue(outdoor.getBlockItems().get(1).getDeployAnimalList().isEmpty());
        player.inv.counts.put(991873, 2);
        outdoor.update(candidate, player);
        assertEquals(1, outdoor.getBlockItems().get(1).getDeployAnimalList().size());
        var unknown = candidate.toBuilder().clearBlockArrangementInfoList().addBlockArrangementInfoList(
                HomeBlockArrangementInfo.newBuilder().setBlockId(1).addDeployAnimalList(HomeAnimalData.newBuilder().setFurnitureId(999999999))).build();
        assertFalse(outdoor.validateArrangement(unknown, player));
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var f=Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return type.cast(((Unsafe)f.get(null)).allocateInstance(type));
    }
    static class I extends Inventory { Map<Integer,Integer> counts; I(){super(null);} @Override public int getItemCountById(int id){return counts.getOrDefault(id,0);} }
    static class P extends Player {
        I inv; GameHome home;
        @Override public Inventory getInventory(){return inv;}
        @Override public GameHome getHome(){return home;}
        @Override public int getCurrentRealmId(){return 1;}
        @Override public int getSceneId(){return 2001;}
    }
    static class Session extends GameSession {
        P p; Session(P p){super(null);this.p=p;}
        @Override public Player getPlayer(){return p;}
        @Override public void send(BasePacket packet){}
    }
}
