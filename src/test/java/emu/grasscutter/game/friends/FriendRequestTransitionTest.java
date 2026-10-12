package emu.grasscutter.game.friends;

import static emu.grasscutter.audit.MultiplayerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.database.FriendshipPersistenceFixture;
import emu.grasscutter.server.game.AccountSessionLease;
import emu.grasscutter.server.game.GameServer;
import java.util.concurrent.atomic.AtomicInteger;
import emu.grasscutter.net.proto.DealAddFriendResultTypeOuterClass.DealAddFriendResultType;
import org.junit.jupiter.api.*;

final class FriendRequestTransitionTest {
    P asker, recipient; Friendship mine, theirs;
    FriendshipPersistenceFixture persistence;
    final java.util.List<emu.grasscutter.server.game.GameSession> leased = new java.util.ArrayList<>();
    static final AtomicInteger NEXT_ACCOUNT = new AtomicInteger();
    static final DealAddFriendResultType ACCEPT = DealAddFriendResultType.DealAddFriendResultType_DEAL_ADD_FRIEND_ACCEPT;
    static final DealAddFriendResultType DECLINE = DealAddFriendResultType.DealAddFriendResultType_DEAL_ADD_FRIEND_REJECT;
    @BeforeEach void setup() throws Exception {
        persistence = new FriendshipPersistenceFixture();
        GameServer.prepareLoginAdmission();
        var s = server(); asker = player(s, 100, null, 3); recipient = player(s, 200, null, 3);
        leased.add(asker.getSession()); leased.add(recipient.getSession());
        assertTrue(asker.getSession().claimAccountLease("friend-transition-asker-" + NEXT_ACCOUNT.incrementAndGet()));
        assertTrue(recipient.getSession().claimAccountLease("friend-transition-recipient-" + NEXT_ACCOUNT.incrementAndGet()));
        mine = new Friendship(recipient, asker, asker); theirs = new Friendship(asker, recipient, asker);
        recipient.friends.addPendingFriend(mine); asker.friends.addPendingFriend(theirs);
    }
    @AfterEach void cleanup() throws Exception {
        try { if (persistence != null) persistence.close(); }
        finally {
            for (var session : leased) {
                if (session.getLeaseAccountId() != null)
                    AccountSessionLease.release(AccountSessionLease.current(session.getLeaseAccountId()));
            }
        }
    }
    @Test void acceptanceClearsBothPendingMapsAndRetryDoesNotDeleteAcceptedRecords() throws Exception {
        recipient.friends.handleFriendRequest(asker.uid, ACCEPT);
        assertTrue(recipient.friends.getPendingFriends().isEmpty()); assertTrue(asker.friends.getPendingFriends().isEmpty());
        recipient.friends.handleFriendRequest(asker.uid, ACCEPT);
        assertSame(mine, recipient.friends.getFriends().get(asker.uid));
        assertSame(theirs, asker.friends.getFriends().get(recipient.uid));
        persistence.awaitWrites();
        assertEquals(1, persistence.saves(mine)); assertEquals(1, persistence.saves(theirs));
        assertEquals(0, persistence.deletes(mine)); assertEquals(0, persistence.deletes(theirs));
    }
    @Test void alreadyAcceptedReverseFriendshipReportsTheAcceptedOutcome() throws Exception {
        theirs.setIsFriend(true);
        recipient.friends.handleFriendRequest(asker.uid, DECLINE);
        var response = recipient.sent.get(recipient.sent.size() - 1);
        var proto = emu.grasscutter.net.proto.DealAddFriendRspOuterClass.DealAddFriendRsp.parseFrom(response.getData());
        assertEquals(ACCEPT, proto.getDealAddFriendResult());
        persistence.awaitWrites();
        assertTrue(mine.isFriend()); assertEquals(0, persistence.deletes(mine));
    }
    @Test void declineClearsBothSidesSoAnotherRequestIsPossible() throws Exception {
        recipient.friends.handleFriendRequest(asker.uid, DECLINE);
        assertTrue(recipient.friends.getPendingFriends().isEmpty()); assertTrue(asker.friends.getPendingFriends().isEmpty());
        persistence.awaitWrites();
        assertEquals(1, persistence.deletes(mine)); assertEquals(1, persistence.deletes(theirs));
        recipient.friends.handleFriendRequest(asker.uid, DECLINE);
        persistence.awaitWrites();
        assertEquals(1, persistence.deletes(mine)); assertEquals(1, persistence.deletes(theirs));
    }
    @Test void offlineAskerAcceptancePersistsBothRecordsAndClearsRecipientPending() throws Exception {
        asker.online = false;
        AccountSessionLease.release(AccountSessionLease.current(asker.getSession().getLeaseAccountId()));
        asker.setSession(null);
        recipient.friends = new OfflineList(recipient, theirs);
        recipient.friends.addPendingFriend(mine);
        recipient.friends.handleFriendRequest(asker.uid, ACCEPT);
        assertTrue(recipient.friends.getPendingFriends().isEmpty());
        assertSame(mine, recipient.friends.getFriends().get(asker.uid));
        assertTrue(theirs.isFriend()); persistence.awaitWrites();
        assertEquals(1, persistence.saves(mine)); assertEquals(1, persistence.saves(theirs));
        recipient.friends.handleFriendRequest(asker.uid, ACCEPT);
        persistence.awaitWrites();
        assertEquals(1, persistence.saves(mine)); assertEquals(0, persistence.deletes(mine));
    }
    static class OfflineList extends FriendsList {
        final Friendship reverse;
        OfflineList(Player owner, Friendship reverse) { super(owner); this.reverse = reverse; }
        @Override protected Friendship getReverseFriendship(Friendship f) { return reverse; }
    }
    @Test void askerCannotAcceptOwnRequest() throws Exception {
        asker.friends.handleFriendRequest(recipient.uid, ACCEPT);
        persistence.awaitWrites();
        assertEquals(1, asker.friends.getPendingFriends().size()); assertEquals(0, persistence.saves(theirs));
    }
}
