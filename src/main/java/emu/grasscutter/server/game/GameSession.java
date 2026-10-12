package emu.grasscutter.server.game;

import static emu.grasscutter.config.Configuration.*;
import static emu.grasscutter.utils.lang.Language.translate;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.Grasscutter.ServerDebugMode;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.server.event.game.SendPacketEvent;
import emu.grasscutter.utils.*;
import io.netty.buffer.*;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import lombok.*;

public class GameSession implements GameSessionManager.KcpChannel {
    private final GameServer server;
    private GameSessionManager.KcpTunnel tunnel;

    @Getter @Setter private Account account;
    @Getter private Player player;

    /** Session-scoped GM playback completions must not drive gameplay state machines. */
    private final ManualCutsceneTracker manualCutscenes = new ManualCutsceneTracker();

    public boolean registerManualCutscene(int cutsceneId) {
        return manualCutscenes.register(cutsceneId);
    }

    public void cancelManualCutscene(int cutsceneId) {
        manualCutscenes.cancel(cutsceneId);
    }

    public boolean consumeManualCutscene(int cutsceneId) {
        return manualCutscenes.consume(cutsceneId);
    }

    @Getter private long encryptSeed = Crypto.ENCRYPT_SEED;
    private byte[] encryptKey = Crypto.ENCRYPT_KEY;

    @Setter private boolean useSecretKey;

    /** Whether this session has already reported a frame that would not decrypt. */
    private boolean reportedBadMagic;
    private boolean logoutStarted;

    /** Packet classes already reported as having no 7.0 CmdId, so each is said once. */
    private static final java.util.Set<String> missingCmdIdReported =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Getter private volatile SessionState state;

    public synchronized void setState(SessionState state) {
        if (closing && state != SessionState.INACTIVE) return;
        this.state = state;
    }

    @Getter @Setter private emu.grasscutter.game.player.FallDamageSample fallDamageSample;

    @Getter private int clientTime;
    @Getter private long lastPingTime;
    private int lastClientSeq = 10;

    public GameSession(GameServer server) {
        this.server = server;
        this.state = SessionState.WAITING_FOR_TOKEN;
        this.lastPingTime = System.currentTimeMillis();

        if (GAME_INFO.useUniquePacketKey) {
            this.encryptKey = new byte[4096];
            this.encryptSeed = Crypto.generateEncryptKeyAndSeed(this.encryptKey);
        }
    }

    public GameServer getServer() {
        return server;
    }

    public InetSocketAddress getAddress() {
        try {
            return tunnel.getAddress();
        } catch (Throwable ignore) {
            return null;
        }
    }

    public boolean useSecretKey() {
        return useSecretKey;
    }

    public String getAccountId() {
        return this.getAccount().getId();
    }

    public synchronized void setPlayer(Player player) {
        this.player = player;
        this.player.setSession(this);
        this.player.setAccount(this.getAccount());
    }

    public boolean isLoggedIn() {
        return this.getPlayer() != null;
    }

    public void updateLastPingTime(int clientTime) {
        this.clientTime = clientTime;
        this.lastPingTime = System.currentTimeMillis();
    }

    public int getNextClientSequence() {
        return ++lastClientSeq;
    }

    public void replayPacket(int opcode, String name) {
        Path filePath = FileUtils.getPluginPath(name);
        File p = filePath.toFile();

        if (!p.exists()) return;

        byte[] packet = FileUtils.read(p);

        BasePacket basePacket = new BasePacket(opcode);
        basePacket.setData(packet);

        send(basePacket);
    }

    private static final java.util.Set<Integer> MUTED_LOG_OPCODES = java.util.Set.of(2497);

    public void logPacket(String sendOrRecv, int opcode, byte[] payload) {
        if (MUTED_LOG_OPCODES.contains(opcode)) return;
        Grasscutter.getLogger()
                .info(sendOrRecv + ": " + PacketOpcodesUtils.getOpcodeName(opcode) + " (" + opcode + ")");
        if (GAME_INFO.isShowPacketPayload) System.out.println(Utils.bytesToHex(payload));
    }

    public void send(BasePacket packet) {

        // The last word on cutscenes. The two places that build one already check this, so nothing
        // should reach here - it is logged rather than dropped silently precisely so that a
        // cutscene the server did send stops being invisible.
        if (GAME_OPTIONS.disableCutscenes && packet.getOpcode() == PacketOpcodes.CutSceneBeginNotify) {
            Grasscutter.getLogger()
                    .info("Suppressed a CutSceneBeginNotify: game.disableCutscenes is on.");
            return;
        }

        if (packet.getOpcode() <= 0) {
            // A non-positive opcode is a message 7.1 has no known CmdId for. Named once per packet
            // class, at debug: there are dozens of them and none is actionable in normal play.
            if (missingCmdIdReported.add(packet.getClass().getSimpleName())) {
                Grasscutter.getLogger()
                        .debug(
                                "{} has no 7.1 CmdId, so it is not being sent.",
                                packet.getClass().getSimpleName());
            }
            return;
        }

        if (packet.shouldBuildHeader()) {
            packet.buildHeader(this.getNextClientSequence());
        }

        switch (GAME_INFO.logPackets) {
            case ALL -> {
                if ((!PacketOpcodesUtils.LOOP_PACKETS.contains(packet.getOpcode())
                        || GAME_INFO.isShowLoopPackets)
                        && !PacketOpcodes.BANNED_PACKETS.contains(packet.getOpcode())) {
                    logPacket("SEND", packet.getOpcode(), packet.getData());
                }
            }
            case WHITELIST -> {
                if (SERVER.debugWhitelist.contains(packet.getOpcode())
                        && !PacketOpcodes.BANNED_PACKETS.contains(packet.getOpcode())) {
                    logPacket("SEND", packet.getOpcode(), packet.getData());
                }
            }
            case BLACKLIST -> {
                if (!SERVER.debugBlacklist.contains(packet.getOpcode())
                        && !PacketOpcodes.BANNED_PACKETS.contains(packet.getOpcode())) {
                    logPacket("SEND", packet.getOpcode(), packet.getData());
                }
            }
            default -> {}
        }

        SendPacketEvent event = new SendPacketEvent(this, packet);
        event.call();
        if (!event.isCanceled()) {
            try {
                packet = event.getPacket();
                var bytes = packet.build();
                if (packet.shouldEncrypt) {
                    if (Grasscutter.getConfig().server.game.useXorEncryption) {
                        Crypto.xor(bytes, packet.useDispatchKey() || !useSecretKey() ? Crypto.DISPATCH_KEY : this.encryptKey);
                    }
                }
                tunnel.writeData(bytes);
            } catch (Exception e) {
                Grasscutter.getLogger()
                        .debug("Unable to send packet {} to client: {}", packet.getOpcode(), e.toString());
            }
        }
    }

    @Override
    public void onConnected(GameSessionManager.KcpTunnel tunnel) {
        this.tunnel = tunnel;
        Grasscutter.getLogger().info(translate("messages.game.connect", this.getAddress().toString()));
    }

    /**
     * Decrypts a frame in place, working out which key it was sent under rather than assuming.
     *
     * <p>The token exchange has a step where the two sides disagree about the key: the client moves
     * to the session key the moment it accepts the response, and the server only knows that happened
     * when a frame arrives. Guessing wrong in either direction is silent - the frame decrypts to
     * noise and is dropped - and on a version migration that is indistinguishable from a client that
     * never replied, which is exactly the false trail this cost us before.
     *
     * <p>The frame magic settles it: XOR is its own inverse, so a wrong key can be undone and the
     * other one tried, and whichever produces the magic is the key the client is actually using.
     * The session then latches onto it, so this costs one extra XOR only while the two sides are out
     * of step.
     */
    private void decryptWithEitherKey(byte[] bytes) {
        var primary = useSecretKey() ? this.encryptKey : Crypto.DISPATCH_KEY;
        Crypto.xor(bytes, primary);
        if (bytes.length >= 2 && readMagic(bytes) == 17767) return;

        // The session key only exists once a seed has been negotiated, so before that there is
        // nothing to fall back to and the frame really is undecryptable.
        var fallback = useSecretKey() ? Crypto.DISPATCH_KEY : this.encryptKey;
        if (fallback == null || fallback == primary) return;

        Crypto.xor(bytes, primary); // undo
        Crypto.xor(bytes, fallback);
        if (bytes.length >= 2 && readMagic(bytes) == 17767) {
            this.setUseSecretKey(!useSecretKey());
            Grasscutter.getLogger()
                    .info(
                            "Client {} is now talking on the {} key - following it.",
                            this.getAddress(),
                            useSecretKey() ? "session" : "dispatch");
            return;
        }
        // Neither key works, so hand the caller the primary decode and let it report the bad magic.
        Crypto.xor(bytes, fallback);
        Crypto.xor(bytes, primary);
    }

    private static int readMagic(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
    }

    @Override
    public void handleReceive(byte[] bytes) {
        if (Grasscutter.getConfig().server.game.useXorEncryption) {
            decryptWithEitherKey(bytes);
        }
        ByteBuf packet = Unpooled.wrappedBuffer(bytes);

        try {
            boolean allDebug = GAME_INFO.logPackets == ServerDebugMode.ALL;
            int prevOpcode = -1, prevHeaderLen = -1, prevPayloadLen = -1, prevStart = -1;
            while (packet.readableBytes() > 0) {
                if (packet.readableBytes() < 12) {
                    return;
                }
                int pktStart = packet.readerIndex();
                int const1 = packet.readShort();
                if (const1 != 17767) {
                    // A frame that does not start with the magic usually means the key is wrong, not
                    // the packet: a game update can ship a new dispatch key, and then every frame
                    // decrypts to noise. With packet logging off that failure was completely silent,
                    // which reads exactly like "the client never connected" - a different problem with
                    // a different fix. So say it once per session regardless, and hand over the first
                    // bytes, which is what a key has to be recovered from.
                    if (!this.reportedBadMagic) {
                        this.reportedBadMagic = true;
                        Grasscutter.getLogger()
                                .warn(
                                        "A {}-byte frame from {} did not decrypt (magic {}, expected 17767) - the {} key does not match this client. First bytes: {}",
                                        bytes.length,
                                        this.getAddress(),
                                        const1,
                                        useSecretKey() ? "session" : "dispatch",
                                        Utils.bytesToHex(
                                                java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 32))));
                    }
                    if (allDebug) {
                        int badOffset = packet.readerIndex() - 2;
                        Grasscutter.getLogger()
                                .error("Bad Data Package Received: got {} ,expect 17767 (bad magic at offset {} of {}-byte frame; prev packet opcode={} headerLen={} payloadLen={} startedAt={})", const1, badOffset, bytes.length, prevOpcode, prevHeaderLen, prevPayloadLen, prevStart);
                        Grasscutter.getLogger()
                                .error("RAW FRAME HEX: {}", Utils.bytesToHex(bytes));
                    }
                    return; // Bad packet
                }
                int opcode = packet.readUnsignedShort();
                int headerLength = packet.readUnsignedShort();
                int payloadLength = packet.readInt();
                // Include the trailer in an overflow-safe bound before allocating either body.
                if (payloadLength < 0 || payloadLength > 1024 * 1024
                        || (long) headerLength + payloadLength + 2 > packet.readableBytes()) {
                    return;
                }
                byte[] header = new byte[headerLength];
                byte[] payload = new byte[payloadLength];

                packet.readBytes(header);
                packet.readBytes(payload);
                int const2 = packet.readShort();
                if (const2 != -30293) {
                    if (allDebug) {
                        Grasscutter.getLogger()
                                .error("Bad Data Package Received: got {} ,expect -30293", const2);
                    }
                    return; // Bad packet
                }

                prevOpcode = opcode;
                prevHeaderLen = headerLength;
                prevPayloadLen = payloadLength;
                prevStart = pktStart;

                switch (GAME_INFO.logPackets) {
                    case ALL -> {
                        if ((!PacketOpcodesUtils.LOOP_PACKETS.contains(opcode) || GAME_INFO.isShowLoopPackets)
                                && !PacketOpcodes.BANNED_PACKETS.contains(opcode)) {
                            logPacket("RECV", opcode, payload);
                        }
                    }
                    case WHITELIST -> {
                        if (SERVER.debugWhitelist.contains(opcode)
                                && !PacketOpcodes.BANNED_PACKETS.contains(opcode)) {
                            logPacket("RECV", opcode, payload);
                        }
                    }
                    case BLACKLIST -> {
                        if (!(SERVER.debugBlacklist.contains(opcode))
                                && !PacketOpcodes.BANNED_PACKETS.contains(opcode)) {
                            logPacket("RECV", opcode, payload);
                        }
                    }
                    default -> {}
                }

                getServer().getPacketHandler().handle(this, opcode, header, payload);
            }
        } catch (Throwable e) {
            // The rest of this datagram is lost either way, but printed to the console it never
            // reached the log, so a dropped packet left the player's action unexplained.
            Grasscutter.getLogger()
                    .error(
                            "Dropped an inbound packet from {}.",
                            this.getPlayer() != null ? this.getPlayer().getUid() : this.getAddress(),
                            e);
        } finally {

            packet.release();
        }
    }

        @Override
        public void handleClose() {
            synchronized (this) {
                if (logoutStarted) return;
                logoutStarted = true;
                closing = true;
                setState(SessionState.INACTIVE);
            }

            Grasscutter.getLogger()
                    .info(translate("messages.game.disconnect", String.valueOf(this.getAddress())));

            finishLogout();
            try {
                send(new BasePacket(PacketOpcodes.ServerDisconnectClientNotify));
            } catch (Throwable ignore) {
                Grasscutter.getLogger().warn("closing {} error", getAddress().getAddress().getHostAddress());
            }
            releaseAccountLease();
            tunnel = null;
        }

    private volatile AccountSessionLease.Lease accountLease;
    private volatile boolean leaseLoaded;
    private volatile boolean componentsReady;
    private volatile boolean unsafeLoader;
    private volatile boolean closing;
    private boolean writeAdmissionClosed;
    private final java.util.concurrent.CompletableFuture<Void> cleanupDone = new java.util.concurrent.CompletableFuture<>();
    private final java.util.concurrent.atomic.AtomicBoolean cleanupStarted = new java.util.concurrent.atomic.AtomicBoolean();

    /** Gameplay admission closes immediately; final cleanup writes keep their ownership. */
    public boolean acceptsGameWork() { return !closing; }

    public synchronized java.util.concurrent.CompletableFuture<Void> loginWorkCompletion() {
        return loginWorkDone;
    }
    public void quarantineCleanupFailure() { unsafeLoader = true; }
    public void quarantineUnstoppedLoader() { unsafeLoader = true; }
    private int loginWorkers;
    private java.util.concurrent.CompletableFuture<Void> loginWorkDone =
            java.util.concurrent.CompletableFuture.completedFuture(null);

    public synchronized boolean beginLoginWork() {
        if (closing) return false;
        if (loginWorkers++ == 0) loginWorkDone = new java.util.concurrent.CompletableFuture<>();
        return true;
    }

    public void endLoginWork() {
        java.util.concurrent.CompletableFuture<Void> completion = null;
        synchronized (this) {
            if (--loginWorkers == 0) completion = loginWorkDone;
        }
        // Logout can acquire scene/player monitors; never invoke it under the session monitor.
        if (completion != null) completion.complete(null);
    }

    public void markComponentsReady() { componentsReady = true; }

    public synchronized boolean claimAccountLease(String accountId) {
        if (closing || leaseRetirement != null) return false;
        if (accountLease != null) return ownsAccountLease();
        accountLease = AccountSessionLease.claim(accountId, this, ACCOUNT.maxPlayer);
        return accountLease != null;
    }

    public boolean ownsAccountLease() { return AccountSessionLease.owns(accountLease); }

    public String getLeaseAccountId() {
        var lease = accountLease;
        return lease == null ? null : lease.accountId();
    }

    public long getAccountSessionGeneration() {
        var lease = accountLease;
        return lease == null ? 0 : lease.generation();
    }

    /** Final producers retain admission until completion; queued writes drain before lease release. */
    public synchronized boolean canSubmitSessionWrite() {
        return !writeAdmissionClosed && ownsAccountLease();
    }

    public void markLeaseLoaded() { leaseLoaded = true; }

    private java.util.concurrent.CompletableFuture<Void> leaseRetirement;

    public java.util.concurrent.CompletableFuture<Void> retireAccountLease() {
        closing = true;
        java.util.concurrent.CompletableFuture<Void> result;
        synchronized (this) {
            if (leaseRetirement == null) {
                var lease = accountLease;
                leaseRetirement = java.util.concurrent.CompletableFuture.allOf(cleanupDone, loginWorkDone)
                        .thenCompose(ignored -> {
                            synchronized (this) {
                                writeAdmissionClosed = true;
                                if (unsafeLoader) return java.util.concurrent.CompletableFuture.failedFuture(
                                        new IllegalStateException("Unfinished loader or cleanup; account remains quarantined"));
                                return leaseLoaded ? LoginPersistenceHandoff.flush()
                                        : java.util.concurrent.CompletableFuture.completedFuture(null);
                            }
                        }).thenRun(() -> AccountSessionLease.release(lease));
            }
            result = leaseRetirement;
        }
        finishLogout();
        return result;
    }

    public void releaseAccountLease() { retireAccountLease(); }

    private void finishLogout() {
        if (!cleanupStarted.compareAndSet(false, true)) return;
        loginWorkCompletion().thenRun(() -> {
            try {
                if (componentsReady && getPlayer() != null) {
                    getPlayer().onLogout();
                    getPlayer().getScriptDepartureCompletion()
                            .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .whenComplete((ignored, failure) -> {
                                if (failure == null) cleanupDone.complete(null);
                                else {
                                    unsafeLoader = true;
                                    cleanupDone.completeExceptionally(failure);
                                    Grasscutter.getLogger().error("Final script save did not complete; account remains quarantined", failure);
                                }
                            });
                } else cleanupDone.complete(null);
            } catch (Throwable failure) {
                unsafeLoader = true;
                cleanupDone.completeExceptionally(failure);
                Grasscutter.getLogger().error("Session logout failed; account remains quarantined", failure);
            }
        });
    }

    public void close() {
        closing = true;
        setState(SessionState.INACTIVE);
        try {
            finishLogout();
            releaseAccountLease();
        } finally {
            var tunnel = this.tunnel;
            if (tunnel != null) tunnel.close();
        }
    }

    public boolean isActive() {
        return acceptsGameWork() && getState() == SessionState.ACTIVE;
    }

    /**
     * Whether the connection is still open, which is a different question from {@link #isActive()} -
     * that one asks whether the player has finished logging in. Anything working the handshake, in
     * front of login, has to ask this one instead.
     */
    public boolean isConnected() {
        return this.tunnel != null;
    }

    public enum SessionState {
        INACTIVE,
        WAITING_FOR_TOKEN,
        WAITING_FOR_LOGIN,
        PICKING_CHARACTER,
        ACTIVE,
        ACCOUNT_BANNED,
        /** Turned away because the server is already at its player limit. */
        SERVER_MAX_PLAYER_OVERFLOW,
        /** Turned away because the database save queues are backed up. */
        DB_OVERLOAD
    }
}
