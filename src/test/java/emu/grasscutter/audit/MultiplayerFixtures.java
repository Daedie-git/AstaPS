package emu.grasscutter.audit;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.friends.FriendsList;
import emu.grasscutter.game.world.*;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.server.game.*;
import java.util.*;
import sun.misc.Unsafe;

/** No constructors that load resources, open sockets or reach persistence. */
public final class MultiplayerFixtures {
    public static <T> T allocate(Class<T> type) {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return AuditFixtures.allocate(type);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    public static void field(Object object, Class<?> owner, String name, Object value) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(object, value);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    public static P player(GameServer server, int uid, W world, int sceneId) {
        P p = allocate(P.class);
        field(p, Player.class, "id", uid);
        p.uid = uid; p.server = server; p.world = world; p.sceneId = sceneId;
        p.position = new Position(); p.rotation = new Position();
        p.sent = new ArrayList<>(); p.online = true;
        p.requests = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(); p.friends = new FriendsList(p);
        p.scene = allocate(Sc.class); p.scene.sceneId = sceneId;
        p.setSceneLoadState(Player.SceneLoadState.LOADED);
        p.setSession(new Session(server));
        server.getPlayers().put(uid, p);
        if (world != null) world.roster.add(p);
        return p;
    }
    public static GameServer server() {
        GameServer s = allocate(GameServer.class);
        field(s, GameServer.class, "players", new HashMap<Integer, Player>());
        return s;
    }
    public static W world(P host, boolean mp) {
        W w = allocate(W.class); w.host = host; w.mp = mp; w.roster = new ArrayList<>(); return w;
    }
    public static class P extends Player {
        public int uid, sceneId; public GameServer server; public W world; public Sc scene;
        public Position position, rotation; public List<BasePacket> sent;
        public boolean online; public FriendsList friends;
        public it.unimi.dsi.fastutil.ints.Int2ObjectMap<emu.grasscutter.game.CoopRequest> requests;
        @Override public int getUid() { return uid; }
        @Override public GameServer getServer() { return server; }
        @Override public World getWorld() { return world; }
        @Override public void setWorld(World w) { world = (W) w; }
        @Override public int getSceneId() { return sceneId; }
        @Override public void setSceneId(int id) { sceneId = id; scene.sceneId = id; }
        @Override public Scene getScene() { return scene; }
        @Override public Position getPosition() { return position; }
        @Override public Position getRotation() { return rotation; }
        @Override public void sendPacket(BasePacket packet) { sent.add(packet); }
        @Override public boolean isOnline() { return online; }
        @Override public FriendsList getFriendsList() { return friends; }
        @Override public it.unimi.dsi.fastutil.ints.Int2ObjectMap<emu.grasscutter.game.CoopRequest> getCoopRequests() { return requests; }
        @Override public emu.grasscutter.game.friends.PlayerProfile getProfile() { return null; }
        @Override public emu.grasscutter.net.proto.OnlinePlayerInfoOuterClass.OnlinePlayerInfo getOnlinePlayerInfo() {
            return emu.grasscutter.net.proto.OnlinePlayerInfoOuterClass.OnlinePlayerInfo.newBuilder().setUid(uid).build();
        }
        @Override public String getNickname() { return "fixture"; }
        @Override public int getWorldLevel() { return 0; }
        @Override public int getProperty(emu.grasscutter.game.props.PlayerProperty prop) { return 0; }
    }
    public static class W extends World {
        public P host; public boolean mp; public List<Player> roster;
        public int removedScene = -1;
        public W() { super((Player) null); }
        @Override public Player getHost() { return host; }
        @Override public boolean isMultiplayer() { return mp; }
        @Override public List<Player> getPlayers() { return roster; }
        @Override public int getPlayerCount() { return roster.size(); }
        @Override public synchronized void addPlayer(Player p) { addPlayer(p, p.getSceneId()); }
        @Override public synchronized void addPlayer(Player p, int sceneId) {
            if (p.getWorld() != null) p.getWorld().removePlayer(p);
            p.setWorld(this); p.setSceneId(sceneId); roster.add(p);
        }
        @Override public synchronized void removePlayer(Player p) {
            removedScene = p.getSceneId(); roster.remove(p); p.setWorld(null);
        }
        @Override public int getWorldLevel() { return 0; }
    }
    public static class Sc extends Scene {
        public int sceneId;
        public Sc() { super(null, null); }
        @Override public int getId() { return sceneId; }
    }
    static class Session extends GameSession {
        final GameServer server;
        Session(GameServer s) { super(null); server = s; }
        @Override public GameServer getServer() { return server; }
    }
}
