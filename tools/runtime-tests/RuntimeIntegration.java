import com.google.gson.GsonBuilder;
import com.google.protobuf.CodedOutputStream;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.config.Configuration;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.ItemParamData;
import emu.grasscutter.database.DatabaseHelper;
import emu.grasscutter.database.DatabaseManager;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.*;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GetPlayerTokenReqOuterClass.GetPlayerTokenReq;
import emu.grasscutter.net.proto.TakePlayerLevelRewardRspOuterClass.TakePlayerLevelRewardRsp;
import emu.grasscutter.server.game.*;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Black-box archive integration: real resources, handlers, players, writers and MongoDB.
 * A packet sink replaces client transport; game logic and persistence use archive implementations.
 */
public final class RuntimeIntegration {
    static final String USER = "runtime_fixture";
    static final String TOKEN = "disposable-runtime-token";
    static final List<Map<String, Object>> results = new ArrayList<>();
    static final Map<String, Object> checkpoint = new LinkedHashMap<>();
    static GameServerPacketHandler router;
    static Sink session;
    static Player player;
    static String phase;

    static final class Sink extends GameSession {
        final List<BasePacket> sent = new CopyOnWriteArrayList<>();
        Sink() { super(Grasscutter.getGameServer()); }
        @Override public void send(BasePacket packet) { sent.add(packet); }
        @Override public boolean isConnected() { return acceptsGameWork(); }
        @Override public InetSocketAddress getAddress() { return new InetSocketAddress("127.0.0.1", 45000); }
    }

    interface Checked { void run() throws Exception; }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void test(String name, Checked action) throws Exception {
        long start = System.nanoTime();
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        try {
            action.run(); result.put("status", "passed");
        } catch (Throwable error) {
            result.put("status", "failed"); result.put("error", error.toString());
            error.printStackTrace();
            throw error;
        } finally {
            result.put("durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            results.add(result); writeResults();
        }
    }
    static void atomicJson(String name, Object value) throws Exception {
        Path target = Path.of(name);
        Path temporary = Path.of(name + ".pending");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(value));
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    static void writeResults() throws Exception {
        atomicJson(phase + "-results.json", results);
    }
    static void completePhase() throws Exception {
        int expected = switch (phase) {
            case "fresh", "activity" -> 2;
            case "write" -> 16;
            case "restart" -> 4;
            default -> throw new IllegalArgumentException(phase);
        };
        if (!System.getProperty("runtime.resourceGapProfile", "").isEmpty()) expected++;
        check(results.size() == expected && results.stream().map(r -> r.get("name")).distinct().count() == expected,
                "Incomplete or duplicate phase assertions");
        check(results.stream().allMatch(r -> "passed".equals(r.get("status"))), "Cannot complete a failed phase");
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of(phase + "-results.json")));
        atomicJson(phase + "-complete.json", Map.of("schemaVersion", 1, "phase", phase,
                "status", "completed", "assertionCount", results.size(),
                "resultsSha256", HexFormat.of().formatHex(digest)));
    }
    static void resourceGapContract() throws Exception {
        String profile = System.getProperty("runtime.resourceGapProfile", "");
        if (profile.isEmpty()) return;
        check(profile.equals("installed-20261011"), "Unknown resource gap profile");
        test("installed resource gaps are explicitly bounded", () -> {
            for (String extension : List.of("json", "tsv", "tsj")) {
                check(!Files.exists(Path.of("resources/ExcelBinOutput/StatuePromoteExcelConfigData." + extension)),
                        "StatuePromote disposition no longer matches inputs");
            }
            check(GameData.getStatuePromoteDataMap().isEmpty(), "StatuePromote unexpectedly loaded");
            check(!GameData.getBlossomRefreshExcelConfigDataMap().isEmpty(), "Entire blossom table missing");
            check(GameData.getBlossomRefreshExcelConfigDataMap().values().stream()
                    .noneMatch(row -> row.getBlossomChestId() == 2), "BLUE blossom row now exists");
            atomicJson(phase + "-resource-gaps.json", Map.of("profile", profile,
                    "statuePromoteMissing", true, "blueBlossomChestMissing", true,
                    "thaiTextMissing", !Files.exists(Path.of("resources/TextMap/TextMapTH.json"))));
        });
    }
    static void logic(Checked action) throws Exception {
        try {
            GameSessionManager.getLogicThread().submit(() -> {
                try { action.run(); } catch (Exception e) { throw new CompletionException(e); }
            }).get(40, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            var threads = java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
            var dump = new StringBuilder();
            for (var thread : threads) {
                dump.append(thread).append('\n');
                for (var frame : thread.getStackTrace()) dump.append("    at ").append(frame).append('\n');
                dump.append("Monitors: ").append(Arrays.toString(thread.getLockedMonitors())).append('\n');
                dump.append("Synchronizers: ").append(Arrays.toString(thread.getLockedSynchronizers())).append('\n');
            }
            Files.writeString(Path.of(phase + "-timeout-threads.txt"), dump);
            throw timeout;
        }
    }
    static void flush() throws Exception { DatabaseHelper.flushForLoginHandoff().get(30, TimeUnit.SECONDS); }
    static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!condition.getAsBoolean()) {
            check(System.nanoTime() < deadline, message); Thread.sleep(20);
        }
    }
    static void route(Sink target, int opcode, byte[] body) throws Exception {
        logic(() -> router.handle(target, opcode, new byte[0], body));
    }
    static Sink login(Account account) throws Exception {
        Sink target = new Sink();
        byte[] body = GetPlayerTokenReq.newBuilder().setAccountUid(account.getId()).setAccountToken(TOKEN).build().toByteArray();
        route(target, PacketOpcodes.GetPlayerTokenReq, body);
        await(() -> target.getState() == GameSession.SessionState.WAITING_FOR_LOGIN || !target.acceptsGameWork(), "Token login timed out");
        check(target.acceptsGameWork(), "Token login rejected fixture");
        if (!phase.equals("fresh")) {
            if (target.getPlayer().getAvatars().getAvatarCount() == 0) {
                logic(() -> {
                    Player fixture = target.getPlayer();
                    fixture.addAvatar(new emu.grasscutter.game.avatar.Avatar(10000007), false);
                    fixture.setMainCharacterId(10000007); fixture.setHeadImage(10000007);
                    var team = fixture.getTeamManager().getCurrentSinglePlayerTeamInfo().getAvatars();
                    team.clear(); team.add(10000007); fixture.save();
                });
                flush();
            }
            // Existing-save fixture: seed through Morphia, as pre-audit saves did. Separate
            // unseeded phases still fail if normal home/activity creation is broken.
            if (!emu.grasscutter.game.home.GameHome.doesHomeExist(target.getPlayer().getUid()))
                DatabaseManager.getGameDatastore().save(emu.grasscutter.game.home.GameHome.create(target.getPlayer().getUid()));
            for (var config : emu.grasscutter.game.activity.ActivityManager.getScheduleActivityConfigMap().values()) {
                if (DatabaseHelper.getPlayerActivityData(target.getPlayer().getUid(), config.getActivityId()) == null)
                    DatabaseManager.getGameDatastore().save(config.getActivityHandler().initPlayerActivityData(target.getPlayer()));
            }
        }
        route(target, PacketOpcodes.PlayerLoginReq, new byte[0]);
        check(target.isActive(), "World login failed");
        check(Grasscutter.getGameServer().getPlayerByUid(target.getPlayer().getUid()) == target.getPlayer(), "Player not registered");
        return target;
    }
    static void close(Sink target) throws Exception {
        logic(target::close);
        target.retireAccountLease().get(40, TimeUnit.SECONDS);
        check(!target.ownsAccountLease(), "Lease not retired");
        check(!Grasscutter.getGameServer().getPlayers().containsValue(target.getPlayer()), "Closed player retained in roster");
    }
    static TakePlayerLevelRewardRsp claim(int rank) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeUInt32(15, rank); out.flush(); session.sent.clear();
        route(session, PacketOpcodes.TakePlayerLevelRewardReq, bytes.toByteArray());
        var replies = session.sent.stream().filter(p -> p.getOpcode() == PacketOpcodes.TakePlayerLevelRewardRsp).toList();
        check(replies.size() == 1, "Rank request must receive one reply");
        return TakePlayerLevelRewardRsp.parseFrom(replies.getFirst().getData());
    }
    static Map<String, Integer> inventory(Player p) {
        Map<String, Integer> state = new TreeMap<>();
        for (GameItem item : p.getInventory()) state.merge("item:" + item.getItemId(), item.getCount(), Math::addExact);
        state.put("currency:201", p.getPrimogems());
        state.put("currency:202", p.getMora());
        state.put("currency:203", p.getCrystals());
        state.put("currency:204", p.getHomeCoin());
        return state;
    }
    static int material() {
        return GameData.getItemDataMap().values().stream().filter(d -> d.getItemType() == ItemType.ITEM_MATERIAL
                && !d.isUseOnGain() && d.getStackLimit() > 100 && d.getMaterialType() == MaterialType.MATERIAL_WOOD)
                .mapToInt(d -> d.getId()).min().orElseThrow();
    }
    static void core() throws Exception {
        test("parallel UID allocation is unique in real MongoDB", () -> {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<Integer>> tasks = new ArrayList<>();
                for (int i = 0; i < 24; i++) tasks.add(pool.submit(() -> DatabaseManager.getNextId(Account.class)));
                Set<Integer> ids = new HashSet<>();
                for (var task : tasks) check(ids.add(task.get(20, TimeUnit.SECONDS)), "Duplicate durable account ID");
                check(ids.size() == 24, "Missing allocated IDs");
            } finally { pool.shutdownNow(); }
        });
        Account account = DatabaseHelper.createAccountWithPassword(USER, "disposable-fixture-password");
        check(account != null, "Fixture account creation failed");
        test("credential rotation survives actual BSON round-trip", () -> {
            account.setToken(TOKEN); DatabaseHelper.updateAccountCredential(account, "token", TOKEN);
            check(TOKEN.equals(DatabaseHelper.getAccountById(account.getId()).getToken()), "Token not persisted");
            check(Objects.equals(account.getPersistenceVersion(), DatabaseHelper.getAccountById(account.getId()).getPersistenceVersion()), "CAS version not persisted");
        });
        test("invalid token cannot admit or create a player", () -> {
            Sink bad = new Sink();
            route(bad, PacketOpcodes.GetPlayerTokenReq, GetPlayerTokenReq.newBuilder().setAccountUid(account.getId()).setAccountToken("wrong").build().toByteArray());
            await(() -> !bad.acceptsGameWork(), "Bad token not rejected");
            check(bad.getPlayer() == null && !bad.ownsAccountLease(), "Invalid token acquired player/ownership");
            check(DatabaseHelper.getPlayerByAccount(account.getId()) == null, "Invalid token wrote a player");
        });
        test("existing-save fixture token and world login load real components", () -> {
            session = login(account); player = session.getPlayer();
            check(player.getAvatars().getAvatarCount() == 1 && player.getInventory().isLoaded(), "Incomplete player admitted");
            flush(); check(DatabaseHelper.getPlayerByUid(player.getUid()) != null, "New player not persisted");
        });
        test("loaded Nahida resource retains its defense modifier formula", () -> {
            var data = GameData.getAbilityData("Avatar_Nahida_ElementalArt");
            check(data != null, "Nahida ability resource missing");
            var property = data.modifiers.get("UNIQUE_Avatar_Nahida_Constellation_2_DefenseDown").properties;
            var retained = (Map<?, ?>) property.getClass().getMethod("values").invoke(property);
            var ratio = (emu.grasscutter.data.common.DynamicFloat) retained.get("Actor_DefenceRatio");
            check(ratio != null, "Defense reduction dropped during actual resource load");
            var specials = new it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap<String>();
            specials.put("DefenseDown_Ratio", .3f);
            check(Math.abs(ratio.get(specials, 0) + .3f) < .000001f, "Defense formula changed");
        });
        logic(() -> player.setProperty(PlayerProperty.PROP_PLAYER_LEVEL, 5, false));
        test("earned rank reward pays once through registered router", () -> {
            var before = inventory(player);
            check(claim(2).getRetcode() == 0, "Earned rank rejected");
            var paid = inventory(player); check(!paid.equals(before), "Reward did not change inventory");
            check(claim(2).getRetcode() == 0 && paid.equals(inventory(player)), "Duplicate rank paid again");
            check(player.getRewardedLevels().contains(2), "Claim marker missing"); flush();
        });
        test("future and malformed rank requests preserve inventory and markers", () -> {
            var before = inventory(player); var markers = Set.copyOf(player.getRewardedLevels());
            check(claim(60).getRetcode() != 0, "Future rank accepted");
            route(session, PacketOpcodes.TakePlayerLevelRewardReq, new byte[]{0x7a, 0x01, 0x03});
            check(before.equals(inventory(player)) && markers.equals(player.getRewardedLevels()), "Invalid claim mutated state");
        });
        test("full bag rejects claim without consuming retry eligibility", () -> {
            var bag = player.getInventory(); var original = bag.getInventoryTab(ItemType.ITEM_MATERIAL);
            var full = new MaterialInventoryTab(0);
            for (GameItem item : bag) if (item.getItemType() == ItemType.ITEM_MATERIAL) full.onAddItem(item);
            var before = inventory(player);
            logic(() -> bag.createInventoryTab(ItemType.ITEM_MATERIAL, full));
            try {
                check(claim(4).getRetcode() != 0, "Full bag claim accepted");
                check(!player.getRewardedLevels().contains(4) && before.equals(inventory(player)), "Rejection consumed reward/state");
            } finally { logic(() -> bag.createInventoryTab(ItemType.ITEM_MATERIAL, original)); }
            check(claim(4).getRetcode() == 0 && player.getRewardedLevels().contains(4), "Claim not retryable after capacity restored");
            flush();
        });
        test("duplicate payment costs are aggregated before debit", () -> {
            logic(() -> {
                player.setProperty(PlayerProperty.PROP_PLAYER_SCOIN, 1000, false);
                check(!player.getInventory().payItems(List.of(new ItemParamData(202, 600), new ItemParamData(202, 600))), "Unaffordable aggregate accepted");
                check(player.getMora() == 1000, "Rejected payment debited Mora");
            });
        });
        test("output stack overflow rejects exchange without payment", () -> {
            int id = material(); var data = GameData.getItemDataMap().get(id);
            logic(() -> {
                check(player.getInventory().tryAddItemParamDatas(List.of(new ItemParamData(id, data.getStackLimit())), ActionReason.PlayerUpgradeReward), "Seed stack failed");
                var before = inventory(player);
                check(!player.getInventory().tryPayAndAddItems(List.of(new ItemParamData(202, 10)), 1,
                        List.of(new GameItem(id, 1)), ActionReason.PlayerUpgradeReward), "Overflow output accepted");
                check(before.equals(inventory(player)), "Rejected exchange changed state");
            }); flush();
        });
        test("fully consumed material is deleted and does not resurrect", () -> {
            int id = material();
            logic(() -> check(player.getInventory().payItems(List.of(new ItemParamData(id, GameData.getItemDataMap().get(id).getStackLimit()))), "Consumption failed"));
            flush();
            check(DatabaseHelper.getInventoryItems(player).stream().noneMatch(i -> i.getItemId() == id), "Consumed item retained in Mongo");
        });
        test("TPS accessories unlock in both acquisition orders and survive material removal", () -> {
            var rows = GameData.getTpsWeaponAccessoryDataMap().values().stream()
                    .sorted(Comparator.comparingInt(emu.grasscutter.data.excels.tps.TpsWeaponAccessoryData::getId)).toList();
            var first = rows.stream().filter(r -> r.getUnlockMaterialId() > 0).findFirst().orElseThrow();
            var second = rows.stream().filter(r -> r.getUnlockMaterialId() > 0 && r.getTpsWeaponId() != first.getTpsWeaponId()).findFirst().orElseThrow();
            logic(() -> {
                var bag = player.getInventory();
                check(bag.addItem(first.getTpsWeaponId()), "First TPS weapon rejected");
                check(bag.addItem(first.getUnlockMaterialId()), "First accessory rejected");
                check(bag.addItem(second.getUnlockMaterialId()), "Second accessory rejected");
                check(bag.addItem(second.getTpsWeaponId()), "Second TPS weapon rejected");
                for (var row : List.of(first, second)) {
                    var gun = emu.grasscutter.game.tps.TpsWeaponSystem.findOwnedWeapon(player, row.getTpsWeaponId());
                    check(gun.getTpsAccessoryIds().contains(row.getId()), "Accessory not unlocked");
                    check(bag.payItem(row.getUnlockMaterialId(), 1), "Accessory consumption failed");
                    check(gun.getTpsAccessoryIds().contains(row.getId()), "Permanent unlock removed");
                }
                var avatar = player.getAvatars().getAvatarById(10000007);
                var gun = emu.grasscutter.game.tps.TpsWeaponSystem.findOwnedWeapon(player, first.getTpsWeaponId());
                check(emu.grasscutter.game.tps.TpsWeaponSystem.wear(player, avatar.getGuid(), List.of(gun.getGuid())) == 0,
                        "Owned avatar TPS wear rejected");
                checkpoint.put("tpsWeapon", first.getTpsWeaponId());
                checkpoint.put("tpsAccessory", first.getId());
                checkpoint.put("tpsMaterial", first.getUnlockMaterialId());
                // Model an old save whose removed gun was never detached.
                avatar.getTpsWeaponIds().add(999999);
                avatar.save();
                player.getTpsLoadout().add(999999);
                player.save();
            });
            flush();
            var stored = DatabaseHelper.getInventoryItems(player);
            for (var row : List.of(first, second)) {
                var gun = stored.stream().filter(i -> i.getItemId() == row.getTpsWeaponId()).findFirst().orElseThrow();
                check(gun.getTpsAccessoryIds().contains(row.getId()), "Mongo lost accessory unlock");
                check(stored.stream().noneMatch(i -> i.getItemId() == row.getUnlockMaterialId()), "Consumed accessory resurrected");
            }
        });
        test("duplicate active login joins saves before replacing ownership", () -> {
            logic(() -> { player.setNickname("PersistedFixture"); player.getQuestGlobalVariables().put(991234, 77); player.save(); });
            Sink old = session; long generation = old.getAccountSessionGeneration();
            session = login(DatabaseHelper.getAccountById(account.getId())); player = session.getPlayer();
            check(!old.acceptsGameWork() && !old.ownsAccountLease(), "Previous owner retained");
            check(session.getAccountSessionGeneration() > generation, "Generation did not advance");
            check(player.getNickname().equals("PersistedFixture") && player.getQuestGlobalVariables().get(991234) == 77, "Replacement loaded stale player");
            check(Grasscutter.getGameServer().getPlayers().size() == 1, "Duplicate online player");
            check(player.getRewardedLevels().containsAll(Set.of(2, 4)), "Replacement lost reward markers");
            check(!player.getAvatars().getAvatarById(10000007).getTpsWeaponIds().contains(999999)
                    && !player.getTpsLoadout().contains(999999), "Inventory load retained stale TPS equipment");
        });
        test("closed generation cannot overwrite acknowledged player state", () -> {
            Sink old = session; Player retired = player; close(old);
            retired.setNickname("LateWriteMustNotPersist"); retired.save(); flush();
            check(DatabaseHelper.getPlayerByUid(retired.getUid()).getNickname().equals("PersistedFixture"), "Late retired write replaced stored player");
            session = login(DatabaseHelper.getAccountById(account.getId())); player = session.getPlayer();
        });
        test("logout acknowledges final player and item state", () -> {
            checkpoint.put("uid", player.getUid()); checkpoint.put("inventory", inventory(player));
            close(session);
            check(DatabaseHelper.getPlayerByUid(player.getUid()).getRewardedLevels().containsAll(Set.of(2, 4)), "Final markers not persisted");
            Files.writeString(Path.of("checkpoint.json"), new GsonBuilder().setPrettyPrinting().create().toJson(checkpoint));
        });
    }
    static void restart() throws Exception {
        var saved = com.google.gson.JsonParser.parseString(Files.readString(Path.of("checkpoint.json"))).getAsJsonObject();
        test("server and Mongo restart preserve player, items and quest variables", () -> {
            session = login(DatabaseHelper.getAccountByName(USER)); player = session.getPlayer();
            check(player.getUid() == saved.get("uid").getAsInt(), "UID changed after restart");
            var actual = new GsonBuilder().create().toJsonTree(inventory(player));
            check(actual.equals(saved.get("inventory")), "Inventory changed after restart");
            check(player.getNickname().equals("PersistedFixture") && player.getQuestGlobalVariables().get(991234) == 77, "Saved player fields lost");
            check(player.getInventory().getItemCountById(material()) == 0, "Consumed item resurrected");
        });
        test("TPS reload preserves unlocks and final removal persists clean equipment", () -> {
            int weaponId = saved.get("tpsWeapon").getAsInt();
            int accessoryId = saved.get("tpsAccessory").getAsInt();
            int materialId = saved.get("tpsMaterial").getAsInt();
            var gun = emu.grasscutter.game.tps.TpsWeaponSystem.findOwnedWeapon(player, weaponId);
            var avatar = player.getAvatars().getAvatarById(10000007);
            check(gun != null && gun.getTpsAccessoryIds().contains(accessoryId), "Restart lost permanent unlock");
            check(player.getInventory().getItemCountById(materialId) == 0, "Consumed accessory reappeared");
            check(avatar.getTpsWeaponIds().contains(weaponId), "Restart lost saved equipment");
            var oldEntity = gun.getWeaponEntity();
            logic(() -> check(player.getInventory().removeItem(gun, 1), "Final TPS removal failed"));
            check(!avatar.getTpsWeaponIds().contains(weaponId) && !player.getTpsLoadout().contains(weaponId), "Removed weapon left equipment reference");
            check(gun.getWeaponEntity() == null, "Removed gun retains an entity");
            if (oldEntity != null) check(!oldEntity.getScene().getWeaponEntities().containsKey(oldEntity.getId()), "Scene retains old gun");
            flush();
            check(DatabaseHelper.getInventoryItems(player).stream().noneMatch(i -> i.getItemId() == weaponId), "Mongo retains exhausted gun");
            var storedAvatar = DatabaseHelper.getAvatars(player).stream().filter(a -> a.getAvatarId() == 10000007).findFirst().orElseThrow();
            check(!storedAvatar.getTpsWeaponIds().contains(weaponId), "Mongo retains stale equipment");
        });
        test("persisted claim markers block repayment after restart", () -> {
            var before = inventory(player);
            check(claim(2).getRetcode() == 0 && claim(4).getRetcode() == 0, "Persisted claim retry not answered");
            check(before.equals(inventory(player)), "Reward paid again after restart");
            close(session);
        });
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 1 && Set.of("fresh", "activity", "write", "restart").contains(args[0]), "Unknown phase");
        phase = args[0]; int exit = 0;
        Files.deleteIfExists(Path.of(phase + "-complete.json"));
        try {
            // Match the executable's initialization order before consulting static configuration.
            Grasscutter.getConfig();
            check(Configuration.DATABASE.game.connectionUri.equals("mongodb://127.0.0.1:37018"), "Unsafe Mongo URI");
            check(Configuration.DATABASE.game.collection.equals("astaps_runtime_test"), "Unsafe database name");
            check(Configuration.DATABASE.server.connectionUri.equals("mongodb://127.0.0.1:37018")
                    && Configuration.DATABASE.server.collection.equals("astaps_runtime_test"), "Unsafe dispatch database");
            check(Configuration.SERVER.http.bindAddress.equals("127.0.0.1")
                    && Configuration.SERVER.game.bindAddress.equals("127.0.0.1"), "Unsafe listener addresses");
            check(Configuration.SERVER.http.bindPort == 28088 && Configuration.SERVER.game.bindPort == 32101, "Unsafe ports");
            Grasscutter.main(new String[]{"-noconsole"});
            router = new GameServerPacketHandler(PacketHandler.class);
            test("exact archive starts HTTP, game ticks and real Mongo", () -> {
                check(DatabaseManager.getGameDatabase().runCommand(new org.bson.Document("ping", 1)).getDouble("ok") == 1, "Mongo ping failed");
                long before = Grasscutter.getGameServer().getCompletedTickCount();
                await(() -> Grasscutter.getGameServer().getCompletedTickCount() > before, "Game ticks stalled");
            });
            resourceGapContract();
            if (phase.equals("fresh")) {
                test("unseeded fresh account completes world login", () -> {
                    Account fresh = DatabaseHelper.createAccountWithPassword("fresh_fixture", "disposable-fixture-password");
                    fresh.setToken(TOKEN); DatabaseHelper.updateAccountCredential(fresh, "token", TOKEN);
                    session = login(fresh);
                    var home = session.getPlayer().getHome();
                    String homeId = home.getId();
                    check(homeId != null && org.bson.types.ObjectId.isValid(homeId), "New home ID missing or malformed");
                    logic(home::save); flush();
                    var reloaded = DatabaseHelper.getHomeByUid(session.getPlayer().getUid());
                    check(reloaded != null && homeId.equals(reloaded.getId()), "Home ID changed after Mongo round-trip");
                    logic(home::save); flush();
                    check(DatabaseManager.getGameDatastore().find(emu.grasscutter.game.home.GameHome.class)
                            .filter(dev.morphia.query.experimental.filters.Filters.eq("ownerUid", session.getPlayer().getUid()))
                            .count() == 1, "Repeated home save inserted a duplicate");
                    close(session);
                });
            } else if (phase.equals("activity")) {
                test("new activity document obtains an ID and persists through normal writer", () -> {
                    var activity = emu.grasscutter.game.activity.PlayerActivityData.of().uid(9999)
                            .activityId(9999).watcherInfoMap(new HashMap<>()).build();
                    activity.save(); flush();
                    String id = activity.getId();
                    check(id != null && org.bson.types.ObjectId.isValid(id), "New activity ID missing or malformed");
                    var reloaded = DatabaseHelper.getPlayerActivityData(9999, 9999);
                    check(reloaded != null && id.equals(reloaded.getId()), "Activity ID changed after Mongo round-trip");
                    activity.setDetail(Map.of("audit", 1)); activity.save(); flush();
                    reloaded = DatabaseHelper.getPlayerActivityData(9999, 9999);
                    check(id.equals(reloaded.getId()) && reloaded.getDetail().contains("audit"), "Repeated activity save lost ID or update");
                    check(DatabaseManager.getGameDatastore().find(emu.grasscutter.game.activity.PlayerActivityData.class)
                            .filter(dev.morphia.query.experimental.filters.Filters.eq("uid", 9999),
                                    dev.morphia.query.experimental.filters.Filters.eq("activityId", 9999))
                            .count() == 1, "Repeated activity save inserted a duplicate");
                });
            } else if (phase.equals("write")) core(); else if (phase.equals("restart")) restart(); else throw new IllegalArgumentException(phase);
        } catch (Throwable error) { error.printStackTrace(); exit = 1; }
        finally { writeResults(); }
        // Bind completion to the final atomically published results. Exit/log gates still
        // verify the real shutdown hook; a marker alone never establishes success.
        if (exit == 0) completePhase();
        // Runs the actual server shutdown hook; the parent verifies its exit and logs.
        System.exit(exit);
    }
}
