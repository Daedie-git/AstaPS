package emu.grasscutter.game.systems;

import emu.grasscutter.game.CoopRequest;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.player.Player.SceneLoadState;
import emu.grasscutter.game.props.EnterReason;
import emu.grasscutter.game.world.World;
import emu.grasscutter.net.proto.EnterTypeOuterClass.EnterType;
import emu.grasscutter.net.proto.PlayerApplyEnterMpResultNotifyOuterClass;
import emu.grasscutter.net.proto.ReasonOuterClass;
import emu.grasscutter.server.game.*;
import emu.grasscutter.server.packet.send.*;
import java.util.concurrent.ConcurrentHashMap;

public class MultiplayerSystem extends BaseGameSystem {
    /** guest uid -> host uid（7.0 移植） */
    private final ConcurrentHashMap<Integer, Integer> guestHostUids = new ConcurrentHashMap<>();

    public MultiplayerSystem(GameServer server) {
        super(server);
    }

    protected World createWorld(Player host, boolean multiplayer) {
        return new World(host, multiplayer);
    }

    private static int supportedPlayerCapacity() {
        // TeamManager allocates at least one avatar per player from this shared budget.
        return Math.max(1, emu.grasscutter.config.Configuration.GAME_OPTIONS.avatarLimits.multiplayerTeam);
    }

    public synchronized void applyEnterMp(Player player, int targetUid) {
        normalizeStaleMultiplayerWorld(player);
        Player target = getServer().getPlayerByUid(targetUid);
        if (target == null || target == player || target.getWorld() == null
                || target.getWorld().getHost() != target
                || !target.getWorld().getPlayers().contains(target)
                || target.getWorld().getPlayerCount() >= supportedPlayerCapacity()) {
            player.sendPacket(new PacketPlayerApplyEnterMpResultNotify(targetUid, "", false, ReasonOuterClass.Reason.Reason_PLAYER_CANNOT_ENTER_MP));
            return;
        }

        if (player.getWorld().isMultiplayer()) {
            return;
        }

        CoopRequest request = target.getCoopRequests().get(player.getUid());

        if (request != null && !request.isExpired()) {

            return;
        }

        request = new CoopRequest(player);
        target.getCoopRequests().put(player.getUid(), request);

        target.sendPacket(new PacketPlayerApplyEnterMpNotify(player));
    }

    public synchronized void applyEnterMpReply(Player hostPlayer, int applyUid, boolean isAgreed) {
        CoopRequest request = hostPlayer.getCoopRequests().remove(applyUid);
        if (request == null || request.isExpired()) {
            return;
        }
        Player requester = request.getRequester();
        normalizeStaleMultiplayerWorld(requester);
        World hostWorld = hostPlayer.getWorld();
        if (hostWorld == null) {
            requester.sendPacket(new PacketPlayerApplyEnterMpResultNotify(hostPlayer, false,
                    ReasonOuterClass.Reason.Reason_PLAYER_CANNOT_ENTER_MP));
            return;
        }
        synchronized (hostWorld) {
            if (hostPlayer.getWorld() != hostWorld || hostWorld.getHost() != hostPlayer
                    || !hostWorld.getPlayers().contains(hostPlayer)
                    || getServer().getPlayerByUid(hostPlayer.getUid()) != hostPlayer
                    || getServer().getPlayerByUid(applyUid) != requester
                    || requester == hostPlayer || requester.getWorld() == null
                    || requester.getWorld().isMultiplayer()
                    || hostWorld.getPlayerCount() >= supportedPlayerCapacity()) {
                requester.sendPacket(new PacketPlayerApplyEnterMpResultNotify(hostPlayer, false,
                        ReasonOuterClass.Reason.Reason_PLAYER_CANNOT_ENTER_MP));
                return;
            }
            requester.sendPacket(new PacketPlayerApplyEnterMpResultNotify(hostPlayer, isAgreed,
                    ReasonOuterClass.Reason.Reason_PLAYER_JUDGE));
            if (!isAgreed) {
                return;
            }
            if (!hostWorld.isMultiplayer()) {
                hostWorld = createWorld(hostPlayer, true);
                hostWorld.addPlayer(hostPlayer);
                hostPlayer.sendPacket(new PacketPlayerEnterSceneNotify(hostPlayer, hostPlayer,
                        EnterType.EnterType_ENTER_SELF, EnterReason.HostFromSingleToMp,
                        hostPlayer.getScene().getId(), hostPlayer.getPosition()));
            }
            requester.getPosition().set(hostPlayer.getPosition());
            requester.getRotation().set(hostPlayer.getRotation());
            hostWorld.addPlayer(requester, hostPlayer.getSceneId());
            guestHostUids.put(requester.getUid(), hostWorld.getHost().getUid());
            requester.sendPacket(new PacketPlayerEnterSceneNotify(requester, hostPlayer,
                    EnterType.EnterType_ENTER_OTHER, EnterReason.TeamJoin,
                    hostPlayer.getScene().getId(), hostPlayer.getPosition()));
        }
    }

    public synchronized boolean leaveCoop(Player player) {
        guestHostUids.remove(player.getUid());

        if (player.getCurHomeWorld().isInHome(player)) {
            return false;
        }

        if (!player.getWorld().isMultiplayer()) {
            return false;
        }

        for (Player p : player.getWorld().getPlayers()) {
            if (p.getSceneLoadState() != SceneLoadState.LOADED) {
                return false;
            }
        }

        World world = createWorld(player, false);
        world.addPlayer(player);

        player.sendPacket(new PacketPlayerEnterSceneNotify(player, EnterType.EnterType_ENTER_OTHER, EnterReason.TeamBack, player.getScene().getId(), player.getPosition()));
        player.sendPacket(new PacketEnterScenePeerNotify(player));

        return true;
    }

    public synchronized boolean kickPlayer(Player player, int targetUid) {
        World party = player.getWorld();
        if (party == null) {
            return false;
        }
        synchronized (party) {
            Player victim = getServer().getPlayerByUid(targetUid);
            if (player.getWorld() != party || !party.isMultiplayer() || party.getHost() != player
                    || !party.getPlayers().contains(player) || victim == null || victim == player
                    || victim.getWorld() != party || !party.getPlayers().contains(victim)
                    || victim.getSceneLoadState() != SceneLoadState.LOADED) {
                return false;
            }
            guestHostUids.remove(victim.getUid());
            World world = createWorld(victim, false);
            world.addPlayer(victim);
            victim.sendPacket(new PacketPlayerEnterSceneNotify(victim,
                    EnterType.EnterType_ENTER_OTHER, EnterReason.TeamKick,
                    victim.getScene().getId(), victim.getPosition()));
            victim.sendPacket(new PacketEnterScenePeerNotify(victim));
            return true;
        }
    }

    /** 7.0 移植：清掉残留的"单人却处于多人世界"状态 */
    private void normalizeStaleMultiplayerWorld(Player player) {
        World previousWorld = player.getWorld();
        if (previousWorld == null || !previousWorld.isMultiplayer() || previousWorld.getPlayerCount() != 1
                || previousWorld.getHost() != player) {
            return;
        }
        World singlePlayerWorld = createWorld(player, false);
        singlePlayerWorld.addPlayer(player);
        this.guestHostUids.remove(player.getUid());
        Grasscutter.getLogger()
                .info("MP stale world reset: uid={} previousWorld={} newWorld={}", player.getUid(),
                        System.identityHashCode(previousWorld), System.identityHashCode(singlePlayerWorld));
    }

    /** 7.0 移植：进场景/登录后校正世界归属 */
    public synchronized World reconcileMultiplayerWorld(Player player) {
        Integer hostUid = this.guestHostUids.get(player.getUid());
        if (hostUid == null) {
            return player.getWorld();
        }
        Player host2 = this.getServer().getPlayerByUid(hostUid);
        if (host2 == null || host2 == player) {
            this.guestHostUids.remove(player.getUid());
            return player.getWorld();
        }
        World hostWorld = host2.getWorld();
        if (hostWorld == null || !hostWorld.isMultiplayer() || hostWorld.getHost() != host2
                || !hostWorld.getPlayers().contains(host2)) {
            this.guestHostUids.remove(player.getUid());
            return player.getWorld();
        }
        if (player.getWorld() != hostWorld) {
            if (hostWorld.getPlayerCount() >= supportedPlayerCapacity()
                    || hostWorld.getPlayers().contains(player)) {
                this.guestHostUids.remove(player.getUid());
                return player.getWorld();
            }
            World previousWorld = player.getWorld();
            hostWorld.addPlayer(player, host2.getSceneId());
            this.guestHostUids.put(player.getUid(), host2.getUid());
            Grasscutter.getLogger()
                    .warn("MP world reconciliation: guest={} host={} previousWorld={} hostWorld={} players={}",
                            player.getUid(), host2.getUid(),
                            previousWorld == null ? "none"
                                    : Integer.valueOf(System.identityHashCode(previousWorld)),
                            System.identityHashCode(hostWorld), hostWorld.getPlayers().size());
        }
        return hostWorld;
    }

    /** 7.0 移植：成员被移出世界时清理映射 */
    public void onPlayerRemovedFromWorld(World world, Player player) {
        if (!world.isMultiplayer()) {
            return;
        }
        Grasscutter.getLogger()
                .info("MP member removed: uid={} host={} world={} playersBefore={}", player.getUid(),
                        world.getHost() == null ? 0 : world.getHost().getUid(), System.identityHashCode(world),
                        world.getPlayers().size());
        if (world.getHost() == player) {
            world.getPlayers().forEach(member -> this.guestHostUids.remove(member.getUid()));
        } else {
            this.guestHostUids.remove(player.getUid());
        }
    }

}
