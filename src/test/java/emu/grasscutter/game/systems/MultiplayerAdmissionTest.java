package emu.grasscutter.game.systems;

import static emu.grasscutter.audit.MultiplayerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import emu.grasscutter.game.CoopRequest;
import org.junit.jupiter.api.*;

final class MultiplayerAdmissionTest {
    emu.grasscutter.server.game.GameServer server; MultiplayerSystem system; P host, guest; W party;
    @BeforeEach void setup() {
        server = server(); system = new MultiplayerSystem(server) {
            @Override protected emu.grasscutter.game.world.World createWorld(emu.grasscutter.game.player.Player host, boolean mp) {
                return world((P) host, mp);
            }
        };
        party = world(null, true);
        host = player(server, 100, party, 3); party.host = host;
        guest = player(server, 101, party, 3);
    }
    @Test void leaveAdmissionSharesTheKickAndApprovalMutationLock() {
        var p = allocate(LeaveProbe.class); p.system = system;
        assertThrows(StopProbe.class, () -> system.leaveCoop(p));
    }
    static class StopProbe extends RuntimeException {}
    static class LeaveProbe extends P {
        MultiplayerSystem system;
        @Override public emu.grasscutter.game.home.HomeWorld getCurHomeWorld() {
            assertTrue(Thread.holdsLock(system), "leave must serialize against kick and approval");
            throw new StopProbe();
        }
    }
    P applicant(int uid) {
        W solo = world(null, false);
        P p = player(server, uid, solo, 5); solo.host = p; return p;
    }
    void approve(P by, P p) {
        assertSame(p, server.getPlayerByUid(p.uid));
        assertSame(by, server.getPlayerByUid(by.uid));
        assertTrue(by.getWorld().getPlayers().contains(by));
        assertFalse(p.getWorld().isMultiplayer());
        by.requests.put(p.uid, new CoopRequest(p)); system.applyEnterMpReply(by, p.uid, true);
    }
    @Test void unrelatedHostCannotBeKickedOrDisbanded() {
        W other = world(null, true);
        P outsider = player(server, 200, other, 5); other.host = outsider;
        player(server, 201, other, 5);
        assertFalse(system.kickPlayer(host, outsider.uid));
        assertSame(other, outsider.getWorld()); assertEquals(2, other.getPlayerCount());
    }
    @Test void hostCanKickOwnLoadedGuestButGuestCannotKick() {
        assertFalse(system.kickPlayer(guest, host.uid));
        assertSame(guest, server.getPlayerByUid(guest.uid));
        assertEquals(emu.grasscutter.game.player.Player.SceneLoadState.LOADED, guest.getSceneLoadState());
        assertSame(party, guest.getWorld()); assertTrue(party.roster.contains(guest));
        assertTrue(system.kickPlayer(host, guest.uid));
        assertFalse(guest.getWorld().isMultiplayer()); assertEquals(1, party.getPlayerCount());
        assertFalse(system.kickPlayer(host, host.uid));
    }
    @Test void rosterAndWorldMustBothAgreeBeforeKick() {
        P outsider = applicant(200); outsider.world = party;
        assertFalse(system.kickPlayer(host, outsider.uid));
    }
    @Test void differentSceneJoinRemovesBeforeInstallingDestinationId() {
        P p = applicant(200); W old = p.world;
        approve(host, p);
        assertEquals(5, old.removedScene, "old-scene roster/entity removal must use old scene");
        assertTrue(old.roster.isEmpty()); assertSame(party, p.getWorld()); assertEquals(3, p.getSceneId());
        assertSame(party, system.reconcileMultiplayerWorld(p));
    }
    @Test void guestTargetedApplicationIsRejected() {
        P p = applicant(200);
        system.applyEnterMp(p, guest.uid);
        assertTrue(guest.requests.isEmpty());
        assertEquals(1, p.sent.size());
    }
    @Test void guestCannotApproveEvenAnExistingRequest() {
        P p = applicant(200); W old = p.world;
        approve(guest, p);
        assertSame(old, p.getWorld()); assertEquals(2, party.getPlayerCount());
    }
    @Test void configuredAvatarBudgetIsAdmissionCapacity() {
        int oldBudget = GAME_OPTIONS.avatarLimits.multiplayerTeam;
        try {
            GAME_OPTIONS.avatarLimits.multiplayerTeam = 3;
            P first = applicant(200), second = applicant(201);
            approve(host, first); approve(host, second);
            assertSame(party, first.getWorld()); assertNotSame(party, second.getWorld());
            assertEquals(3, party.getPlayerCount());
        } finally { GAME_OPTIONS.avatarLimits.multiplayerTeam = oldBudget; }
    }
    @Test void twoConcurrentApprovalsCannotUseTheLastSlotTwice() throws Exception {
        int oldBudget = GAME_OPTIONS.avatarLimits.multiplayerTeam;
        try {
            GAME_OPTIONS.avatarLimits.multiplayerTeam = 3;
            P a = applicant(200), b = applicant(201);
            host.requests.put(a.uid, new CoopRequest(a)); host.requests.put(b.uid, new CoopRequest(b));
            var start = new java.util.concurrent.CountDownLatch(1);
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            Runnable one = () -> { try { start.await(); system.applyEnterMpReply(host, a.uid, true); } catch (Throwable t) { failures.add(t); } };
            Runnable two = () -> { try { start.await(); system.applyEnterMpReply(host, b.uid, true); } catch (Throwable t) { failures.add(t); } };
            Thread t1 = new Thread(one), t2 = new Thread(two);
            t1.start(); t2.start(); start.countDown(); t1.join(5000); t2.join(5000);
            assertFalse(t1.isAlive()); assertFalse(t2.isAlive()); assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(3, party.getPlayerCount());
        } finally { GAME_OPTIONS.avatarLimits.multiplayerTeam = oldBudget; }
    }
}
