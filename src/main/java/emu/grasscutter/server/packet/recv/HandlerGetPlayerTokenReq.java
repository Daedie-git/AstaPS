package emu.grasscutter.server.packet.recv;

import static emu.grasscutter.config.Configuration.ACCOUNT;

import emu.grasscutter.*;
import emu.grasscutter.database.DatabaseHelper;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.server.event.game.PlayerCreationEvent;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.game.GameSessionManager;
import emu.grasscutter.server.game.PendingLogoutSaves;
import emu.grasscutter.server.game.GameSession.SessionState;
import emu.grasscutter.net.proto.GetPlayerTokenReqOuterClass.GetPlayerTokenReq;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.packet.send.PacketGetPlayerTokenRsp;
import emu.grasscutter.utils.*;
import emu.grasscutter.utils.helpers.ByteHelper;
import java.nio.ByteBuffer;
import java.security.Signature;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;

@Opcodes(PacketOpcodes.GetPlayerTokenReq)
public class HandlerGetPlayerTokenReq extends PacketHandler {
    // Read with ProtoRead rather than parseFrom: a mismatched field type makes parseFrom throw
    // instead of returning the fields that still line up. The numbers come from the generated
    // class so they follow the protocol; they used to be pinned to a 7.0 capture (2, 6, 588, 932),
    // which a 7.1 client does not use, so every token check failed and the session was closed.
    private static final int F_ACCOUNT_UID = GetPlayerTokenReq.ACCOUNT_UID_FIELD_NUMBER;
    private static final int F_ACCOUNT_TOKEN = GetPlayerTokenReq.ACCOUNT_TOKEN_FIELD_NUMBER;
    private static final int F_KEY_ID = GetPlayerTokenReq.KEY_ID_FIELD_NUMBER;
    private static final int F_CLIENT_RAND_KEY = GetPlayerTokenReq.CLIENT_RAND_KEY_FIELD_NUMBER;

    /**
     * Sessions with a login under way. The client may send GetPlayerTokenReq again while one is,
     * and the session stays WAITING_FOR_TOKEN until it ends, so without this a resend would start
     * a second login for the same session alongside the first.
     */
    private static final java.util.Set<GameSession> LOGGING_IN =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Each account's newest login. A later login for the account supersedes an earlier one still
     * under way, so two reconnects can never both load the account.
     */
    private static final java.util.Map<String, Login> CURRENT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Orders login attempts as they arrive. */
    private static final java.util.concurrent.atomic.AtomicLong ATTEMPTS =
            new java.util.concurrent.atomic.AtomicLong();

    /** Each account's newest attempt that authenticated; an older one that authenticates later loses. */
    private static final java.util.Map<String, Long> ACCEPTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.atomic.AtomicInteger LOGIN_THREADS =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Runs the database half of logins: the token check, the player lookup, the IP-ban check and
     * the avatar and inventory load. Every player's packets are handled one at a time on the logic
     * thread, so doing those there made each login stall everyone for its database round trips -
     * after a restart, a few dozen reconnecting clients queued behind each other until the last
     * ones timed out, reconnected and queued again. Anything that touches game state or sends to
     * the client still goes back to the logic thread.
     *
     * <p>Separate from the pool {@link Player#loadFromDatabase} submits its loads to, which this
     * waits on: sharing it would let a burst of logins fill every thread with waiters.
     */
    private static final java.util.concurrent.ExecutorService LOGIN_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(
                    4,
                    runnable -> {
                        var thread = new Thread(runnable, "login-" + LOGIN_THREADS.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });

    /** The login steps, run either across the login and logic threads or inline (asyncLogin off). */
    private record Login(
            GameSession session,
            boolean async,
            String accountId,
            String clientRandKey,
            int keyId,
            long attempt,
            java.util.concurrent.atomic.AtomicReference<Player> player,
            java.util.concurrent.atomic.AtomicInteger databaseSteps,
            java.util.concurrent.atomic.AtomicBoolean ended,
            java.util.concurrent.CompletableFuture<Void> stepsSettled) {
        Login(GameSession session, boolean async, String accountId, String clientRandKey, int keyId) {
            this(session, async, accountId, clientRandKey, keyId, ATTEMPTS.incrementAndGet(),
                    new java.util.concurrent.atomic.AtomicReference<>(),
                    new java.util.concurrent.atomic.AtomicInteger(),
                    new java.util.concurrent.atomic.AtomicBoolean(),
                    new java.util.concurrent.CompletableFuture<>());
        }

        /**
         * Completes once nothing this login started can still write: its database steps, the
         * player's loaders (a timed-out one may still be running) and that player's queued writes.
         */
        java.util.concurrent.CompletableFuture<Void> settled() {
            return this.stepsSettled.thenCompose(ignored -> {
                var loaded = this.player.get();
                if (loaded == null) return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                return loaded.whenLoadersExited()
                        .thenCompose(exited -> DatabaseHelper.whenPlayerWritesDone(loaded.getUid()));
            });
        }

        /** Runs a step that only does database work. */
        void offLogic(Runnable step) {
            this.databaseSteps.incrementAndGet();
            Runnable counted = () -> {
                try {
                    this.guarded(step);
                } finally {
                    if (this.databaseSteps.decrementAndGet() == 0 && this.ended.get()) this.stepsSettled.complete(null);
                }
            };
            if (this.async) LOGIN_EXECUTOR.execute(counted);
            else counted.run();
        }

        /** Runs a step that touches game state or the client. */
        void onLogic(Runnable step) {
            if (this.async) GameSessionManager.getLogicThread().execute(() -> this.guarded(step));
            else this.guarded(step);
        }

        /** Skips steps for a client that has gone, and drops the client if a step throws. */
        private void guarded(Runnable step) {
            if (!this.session.isConnected()) {
                this.done();
                return;
            }
            try {
                step.run();
            } catch (Throwable t) {
                Grasscutter.getLogger()
                        .error("Login for account '{}' from {} failed.", this.accountId, this.session.getAddress(), t);
                this.close();
            }
        }

        /**
         * Ends this login, letting the session start another. The account's next login waits
         * until this one has settled, however it ended (finished, refused, dropped or replaced).
         */
        void done() {
            LOGGING_IN.remove(this.session);
            CURRENT.remove(this.accountId, this);
            if (!this.ended.compareAndSet(false, true)) return;
            if (this.databaseSteps.get() == 0) this.stepsSettled.complete(null);
            PendingLogoutSaves.record(this.accountId, this.settled());
        }

        /** Whether a later login for the same account has not taken over from this one. */
        boolean current() {
            return CURRENT.get(this.accountId) == this;
        }

        void close() {
            this.done();
            Runnable closing = () -> {
                // A player that never finished logging in is not the account's real state;
                // saving it on close would overwrite whatever logs in next.
                var player = this.session.getPlayer();
                if (player != null && Grasscutter.getGameServer().getPlayers().get(player.getUid()) != player) {
                    this.session.detachPlayer();
                    player.onLogout(false);
                }
                this.session.close();
            };
            if (this.async) GameSessionManager.getLogicThread().execute(closing);
            else closing.run();
        }
    }

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var accountId = ProtoRead.string(payload, F_ACCOUNT_UID);
        var accountToken = ProtoRead.string(payload, F_ACCOUNT_TOKEN);
        var clientRandKey = ProtoRead.string(payload, F_CLIENT_RAND_KEY);
        var keyId = (int) ProtoRead.varint(payload, F_KEY_ID);

        if (!LOGGING_IN.add(session)) return;

        var login =
                new Login(
                        session, Grasscutter.getConfig().server.game.asyncLogin, accountId, clientRandKey, keyId);
        login.offLogic(() -> authenticate(login, accountToken));
    }

    /** Database: checks the token. */
    private static void authenticate(Login login, String accountToken) {
        var session = login.session();
        var account = DispatchUtils.authenticate(login.accountId(), accountToken);

        if (account == null && !DebugConstants.ACCEPT_CLIENT_TOKEN) {
            Grasscutter.getLogger()
                    .warn(
                            "Token check failed for account '{}' from {} - closing the session.",
                            login.accountId(),
                            session.getAddress());
            login.close();
            return;
        } else if (account == null && DebugConstants.ACCEPT_CLIENT_TOKEN) {
            account = DispatchUtils.getAccountById(login.accountId());
            if (account == null) {
                login.close();
                return;
            }
        }

        if (!java.util.Objects.equals(account.getId(), login.accountId())) {
            login.close();
            return;
        }
        var authenticated = account;
        login.onLogic(() -> takeOver(login, authenticated, false));
    }

    /**
     * Game state: replaces an earlier session of the same account and checks the player limit.
     * Runs again after waiting for the account's last logout saves, as the account may have
     * changed hands meanwhile.
     */
    private static void takeOver(Login login, emu.grasscutter.game.Account account, boolean resumed) {
        var session = login.session();
        if (resumed) {
            if (!login.current()) {
                login.close();
                return;
            }
        } else {
            // Authentication can finish out of order; the attempt that arrived last wins.
            if (ACCEPTED.merge(login.accountId(), login.attempt(), Math::max) != login.attempt()) {
                login.close();
                return;
            }
            var previous = CURRENT.put(login.accountId(), login);
            // Closing it publishes its loaders and writes as pending, which this login waits for below.
            if (previous != null && previous != login) previous.close();
        }
        session.setAccount(account);

        boolean kicked = false;
        var exists = Grasscutter.getGameServer().getPlayerByAccountId(login.accountId());
        if (exists != null) {
            var existsSession = exists.getSession();
            if (existsSession != session) {
                if (existsSession == null) {
                    exists.onLogout();
                } else {
                    // Close fully now, not when the transport reports it: until then the old
                    // session's queued packets would still change and save the old player.
                    existsSession.close();
                    existsSession.handleClose();
                }
                Grasscutter.getLogger()
                    .warn("Player {} was kicked due to duplicated login", account.getUsername());
                kicked = true;
            }
        }

        // A session that finished its login but has not registered its player yet (it is still
        // picking a character, or was refused) holds the account too; displace it like a kick.
        for (var other : GameSessionManager.getSessions()) {
            var otherPlayer = other.getPlayer();
            if (other == session || otherPlayer == null || other.getAccount() == null
                    || !login.accountId().equals(other.getAccount().getId())
                    || Grasscutter.getGameServer().getPlayers().get(otherPlayer.getUid()) == otherPlayer) {
                continue;
            }
            int otherUid = otherPlayer.getUid();
            other.close();
            other.handleClose(); // Unregistered: leaves the world without saving, then detaches.
            other.detachPlayer();
            if (otherUid > 0) {
                PendingLogoutSaves.record(login.accountId(), DatabaseHelper.whenPlayerWritesDone(otherUid));
            }
        }

        if (!kicked) {

            if (ACCOUNT.maxPlayer > -1
                && Grasscutter.getGameServer().getPlayers().size() >= ACCOUNT.maxPlayer) {
                login.close();
                return;
            }
        }

        // Read the player back only after their last logout's saves have landed, or a quick
        // reconnect loads the state from before them.
        var pending = PendingLogoutSaves.get(login.accountId());
        if (pending != null && !pending.isDone()) {
            pending.copy()
                .orTimeout(LOGOUT_SAVE_WAIT_SECONDS, TimeUnit.SECONDS)
                .whenCompleteAsync(
                        (ignored, timedOut) -> {
                            if (timedOut != null) {
                                Grasscutter.getLogger()
                                        .warn("Refused login for account '{}': its last logout is still saving.",
                                                login.accountId());
                                login.close();
                                return;
                            }
                            login.onLogic(() -> takeOver(login, account, true));
                        },
                        GameSessionManager.getLogicThread());
            return;
        }

        var event = new PlayerCreationEvent(session, Player.class);
        event.call();

        var playerClass = event.getPlayerClass();
        login.offLogic(() -> findPlayer(login, playerClass));
    }

    /** How long a login waits for the account's previous logout saves before refusing; above the 30 s load bound. */
    private static final int LOGOUT_SAVE_WAIT_SECONDS = 45;

    /** Database: finds or creates the player and applies an IP ban. */
    private static void findPlayer(Login login, Class<? extends Player> playerClass) {
        if (!login.current()) {
            login.close();
            return;
        }
        var session = login.session();
        var player = DatabaseHelper.getPlayerByAccount(session.getAccount(), playerClass);
        if (player != null) login.player().set(player);

        if (player == null) {
            var nextPlayerUid =
                DatabaseHelper.getNextPlayerId(session.getAccount().getReservedPlayerUid());

            try {
                player = playerClass.getDeclaredConstructor(GameSession.class).newInstance(session);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not create a " + playerClass.getName(), e);
            }

            login.player().set(player);
            DatabaseHelper.generatePlayerUid(player, nextPlayerUid);
        }

        // An IP ban takes the account with it: without that, the same person just registers again
        // from the same address. The account ban is what the client is actually told about, since
        // it is the one the ban screen can explain.
        var clientIp = session.getAddress().getAddress().getHostAddress();
        var ipBan = DatabaseHelper.getBannedIp(clientIp);
        if (ipBan != null && ipBan.isActiveAt(System.currentTimeMillis() / 1000)) {
            var bannedAccount = session.getAccount();
            if (!bannedAccount.isBanned()) {
                bannedAccount.setBanned(true);
                bannedAccount.setBannedByIp(clientIp);
                bannedAccount.setBanReason(DatabaseHelper.IP_BAN_REASON_PREFIX + clientIp);
                bannedAccount.setBanStartTime((int) (System.currentTimeMillis() / 1000));
                bannedAccount.setBanEndTime(ipBan.accountBanEndTime());
                bannedAccount.save();
                Grasscutter.getLogger()
                    .warn("IP {} is banned until {}; account {} was banned with it.",
                        clientIp, ipBan.getEndTime(), bannedAccount.getUsername());
            }
        }

        var found = player;
        login.onLogic(() -> admit(login, found));
    }

    /** Game state: turns the login away if banned, full or overloaded, before loading anything. */
    private static void admit(Login login, Player player) {
        if (!login.current()) {
            login.close();
            return;
        }
        var session = login.session();
        session.setPlayer(player);

        if (session.getAccount().isBanned()) {
            session.setState(SessionState.ACCOUNT_BANNED);
            String banReason = session.getAccount().getBanReason();
            if (banReason == null || banReason.trim().isEmpty()) {
                banReason = "FORBID_CHEATING_PLUGINS";
            }
            session.send(
                new PacketGetPlayerTokenRsp(
                    session, 21, banReason, session.getAccount().getBanEndTime()));
            login.done();
            return;
        }

        // Refuse the login while the server is already at its player limit. Unlike the guards
        // below, the client has its own wording for this one, so it gets a plain retcode.
        if (ACCOUNT.maxPlayer > -1
                && Grasscutter.getGameServer().getPlayers().size() >= ACCOUNT.maxPlayer) {
            session.setState(SessionState.SERVER_MAX_PLAYER_OVERFLOW);
            session.send(
                new PacketGetPlayerTokenRsp(session, Retcode.RET_MP_ALLOW_ENTER_PLAYER_FULL));
            Grasscutter.getLogger()
                .info("Refused uid {}: the server is full.", session.getPlayer().getUid());
            login.done();
            return;
        }

        // Refuse the login while the save queues are backed up. Letting a player in at this point
        // makes it worse: loading them is itself database work, and every save they then generate
        // joins the same queue. Turning them away is what lets it drain.
        if (isDatabaseOverloaded()) {
            session.setState(SessionState.DB_OVERLOAD);
            session.send(new PacketGetPlayerTokenRsp(session, "Server is overloaded, try again shortly"));
            Grasscutter.getLogger()
                .warn(
                    "Refused uid {}: database queues are backed up (default {}/{}, account {}/{},"
                        + " item {}/{}, group {}/{}).",
                    session.getPlayer().getUid(),
                    queueSize(DatabaseHelper.getEventExecutor()),
                    DatabaseHelper.DEFAULT_QUEUE_CAPACITY,
                    queueSize(DatabaseHelper.getEventExecutorAccount()),
                    DatabaseHelper.ACCOUNT_QUEUE_CAPACITY,
                    queueSize(DatabaseHelper.getEventExecutorItem()),
                    DatabaseHelper.ITEM_QUEUE_CAPACITY,
                    queueSize(DatabaseHelper.getEventExecutorGroup()),
                    DatabaseHelper.GROUP_QUEUE_CAPACITY);
            login.done();
            return;
        }

        login.offLogic(() -> load(login, player));
    }

    /** Database: loads the player's avatars, inventory and the rest. */
    private static void load(Login login, Player player) {
        if (!login.current()) {
            login.close();
            return;
        }
        try {
            player.loadFromDatabase();
        } catch (IllegalStateException e) {
            // Load failed or timed out: drop this one client rather than leave it hanging.
            Grasscutter.getLogger().error("Refused uid {}: {}", player.getUid(), e.getMessage(), e.getCause());
            login.close();
            return;
        }
        login.onLogic(() -> respond(login));
    }

    /** Client: answers with the session key. */
    private static void respond(Login login) {
        if (!login.current()) {
            login.close();
            return;
        }
        var session = login.session();
        var keyId = login.keyId();
        var clientRandKey = login.clientRandKey();

        if (Grasscutter.getConfig().server.game.useXorEncryption) {
            session.setState(SessionState.WAITING_FOR_LOGIN);

            if (keyId > 0) {
                var encryptSeed = session.getEncryptSeed();
                try {
                    var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    cipher.init(Cipher.DECRYPT_MODE, Crypto.CUR_SIGNING_KEY);

                    var clientSeedEncrypted = Utils.base64Decode(clientRandKey);
                    var clientSeed = ByteBuffer.wrap(cipher.doFinal(clientSeedEncrypted)).getLong();

                    var combinedSeed = encryptSeed ^ clientSeed;
                    var seedBytes = ByteBuffer.wrap(new byte[8]).putLong(combinedSeed).array();

                    cipher.init(Cipher.ENCRYPT_MODE, Crypto.EncryptionKeys.get(keyId));
                    var seedEncrypted = cipher.doFinal(seedBytes);

                    var privateSignature = Signature.getInstance("SHA256withRSA");
                    privateSignature.initSign(Crypto.CUR_SIGNING_KEY);
                    privateSignature.update(seedBytes);

                    // Exactly ONE response per request. Sending several candidates back to back was
                    // meant to test the field map faster, but a real server answers once, and a
                    // duplicate response to a finished request is its own reason for a client to
                    // give up - which would mask the very thing the candidates were testing. The
                    // client reopens this exchange every 30-60 seconds by itself, so one candidate
                    // per request still covers the whole rotation in a few minutes.
                    var rsp = new PacketGetPlayerTokenRsp(
                            session,
                            Utils.base64Encode(seedEncrypted),
                            Utils.base64Encode(privateSignature.sign()),
                            keyId);
                    session.send(rsp);
                    switchWireKey(session);
                } catch (Exception ignored) {

                    Grasscutter.getLogger().error("GetPlayerTokenReq RSA failed (key_id={}, clientRandKey len={}): {}",
                        keyId,
                        clientRandKey.isEmpty() ? 0 : Utils.base64Decode(clientRandKey).length,
                        ignored.getClass().getSimpleName() + ": " + ignored.getMessage());
                    var clientBytes = Utils.base64Decode(clientRandKey);
                    var seed = ByteHelper.longToBytes(encryptSeed);
                    Crypto.xor(clientBytes, seed);

                    var base64str = Utils.base64Encode(clientBytes);
                    session.send(new PacketGetPlayerTokenRsp(session, base64str, "bm90aGluZyBoZXJl", keyId));
                    switchWireKey(session);
                }
            } else {
                session.send(new PacketGetPlayerTokenRsp(session, keyId));
            }
        } else {
            session.setState(SessionState.WAITING_FOR_LOGIN);
            session.send(new PacketGetPlayerTokenRsp(session, keyId));
        }
        login.done();
    }

    /**
     * Moves the connection onto the negotiated session key.
     *
     * <p>This used to be skipped on 7.x, because the client could not read our response and so never
     * moved with us, and switching alone would have turned its pings - the only signal still coming
     * back - into noise. That trade is gone: the session now works out which key a frame arrived
     * under instead of assuming, so it follows the client either way and nothing is lost by
     * switching. Keeping the skip would be actively harmful now, because the moment the field map is
     * right the client switches and the server has to be there with it.
     */
    private static void switchWireKey(GameSession session) {
        session.setUseSecretKey(true);
    }

    private static boolean isDatabaseOverloaded() {
        return DatabaseHelper.isThreadPoolOverloaded(
                    (ThreadPoolExecutor) DatabaseHelper.getEventExecutor(),
                    DatabaseHelper.DEFAULT_QUEUE_CAPACITY)
                || DatabaseHelper.isThreadPoolOverloaded(
                    (ThreadPoolExecutor) DatabaseHelper.getEventExecutorAccount(),
                    DatabaseHelper.ACCOUNT_QUEUE_CAPACITY)
                || DatabaseHelper.isThreadPoolOverloaded(
                    (ThreadPoolExecutor) DatabaseHelper.getEventExecutorItem(),
                    DatabaseHelper.ITEM_QUEUE_CAPACITY)
                || DatabaseHelper.isThreadPoolOverloaded(
                    (ThreadPoolExecutor) DatabaseHelper.getEventExecutorGroup(),
                    DatabaseHelper.GROUP_QUEUE_CAPACITY);
    }

    private static int queueSize(java.util.concurrent.ExecutorService executor) {
        return ((ThreadPoolExecutor) executor).getQueue().size();
    }
}
