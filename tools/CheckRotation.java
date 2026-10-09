import com.google.gson.*;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.Command;
import emu.grasscutter.command.commands.CatalogCommand;
import emu.grasscutter.game.gacha.*;
import emu.grasscutter.server.game.GameServer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javassist.ClassPool;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import javassist.expr.NewExpr;
import sun.misc.Unsafe;

/** Offline clock/storage tests plus actual deployed hook and command execution. */
public final class CheckRotation {
    private static final Gson GSON = new Gson();
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static final class TestClock extends Clock {
        final AtomicReference<Instant> time;
        final ZoneId zone;
        TestClock(Instant time) { this(new AtomicReference<>(time), ZoneOffset.UTC); }
        TestClock(AtomicReference<Instant> time, ZoneId zone) { this.time = time; this.zone = zone; }
        public ZoneId getZone() { return zone; }
        public Clock withZone(ZoneId zone) { return new TestClock(time, zone); }
        public Instant instant() { return time.get(); }
        void day(String day) { time.set(LocalDate.parse(day).atStartOfDay(ZONE).toInstant()); }
    }
    private static void writeState(Path root, int index, String day) throws Exception {
        Files.writeString(root.resolve("rotation-state.json"), "{\"index\":" + index + ",\"day\":\"" + day + "\"}");
    }
    private static void checkHooks(String jar) throws Exception {
        var pool = new ClassPool(false);
        pool.appendSystemPath();
        pool.appendClassPath(jar);
        var catalogRegistration = new boolean[]{false};
        pool.get("emu.grasscutter.command.CommandMap").getDeclaredMethod("scan").instrument(new ExprEditor() {
            public void edit(NewExpr expression) {
                if (expression.getClassName().equals(CatalogCommand.class.getName())) catalogRegistration[0] = true;
            }
        });
        check(catalogRegistration[0], "Catalog command must be explicitly registered");
        for (var spec : new String[][]{
            {"emu.grasscutter.game.gacha.GachaSystem", "load", "initialize"},
            {"emu.grasscutter.game.gacha.GachaSystem", "createProto", "refresh"},
            {"emu.grasscutter.command.CommandMap", "scan", "registerCommand"}}) {
            var found = new boolean[]{false};
            pool.get(spec[0]).getDeclaredMethod(spec[1]).instrument(new ExprEditor() {
                public void edit(MethodCall call) {
                    if (call.getMethodName().equals(spec[2]) && (spec[2].equals("registerCommand") || call.getClassName().equals(CatalogRotation.class.getName()))) found[0] = true;
                }
            });
            check(found[0], "Missing installed hook: " + spec[1]);
        }
    }
    private static void validateCatalog(JsonObject config, JsonObject baseline) throws Exception {
        var phases = config.getAsJsonArray("phases");
        check(phases.size() == 9, "Nine themes");
        Map<Integer, Integer> roster = new HashMap<>();
        check(phases.get(1).getAsJsonObject().get("name").getAsString().equals("Diluc plunge and classic Mondstadt"), "Diluc set immediately follows current set");
        for (var entry : phases) {
            var phase = entry.getAsJsonObject();
            var objects = phase.getAsJsonArray("banners");
            check(objects.get(0).equals(baseline), "Standard content and rates unchanged");
            var banners = GSON.fromJson(objects, GachaBanner[].class);
            check(banners.length == 7, "Seven visible slots");
            for (int i = 0; i < 7; i++) {
                var b = banners[i]; b.onLoad(); b.ensureFallback4Pools();
                check(b.getScheduleId() == (i == 0 ? 200 : i < 5 ? 7100+i : 7195+i), "Stable schedule IDs");
                check(b.getCost(1).getId() == (i == 0 ? 224 : 223), "Fate type");
                check(b.getCost(10).getCount() == 10, "Ten-pull cost");
                check(b.getEventChance(4) == 50 && b.getEventChance(5) == 50, "Featured chances unchanged");
                for (int pull = 1; pull <= 80; pull++) check(b.getWeight(5, pull) == (pull <= 49 ? 200 : pull >= 70 ? 10000 : 200+(pull-49)*9800/21), "Five-star curve");
                for (int pull = 1; pull <= 12; pull++) check(b.getWeight(4, pull) == (pull <= 6 ? 1000 : pull >= 10 ? 10000 : 1000+(pull-6)*9000/4), "Four-star curve");
                if (i > 0 && i < 5) {
                    var character = phase.getAsJsonArray("characters").get(i-1).getAsJsonObject();
                    int card = character.get("card").getAsInt();
                    check(Arrays.equals(b.getRateUpItems5(), new int[]{card}), "Character identity");
                    roster.merge(card, 1, Integer::sum);
                    check(b.getGachaType() == 400 && b.getFallbackItems5Pool2().length == 0, "Character routing");
                } else if (i >= 5) {
                    int start = (i-5)*2;
                    int[] pair = new int[2];
                    for (int j = 0; j < 2; j++) pair[j] = phase.getAsJsonArray("characters").get(start+j).getAsJsonObject().get("signature").getAsInt();
                    check(Arrays.equals(b.getRateUpItems5(), pair), "Matching signature pairs");
                    check(b.getGachaType() == 302 && b.getFallbackItems5Pool1().length == 0, "Weapon routing");
                    for (int weapon : pair) check(b.isValidEpitomizedWish(weapon), "Server target selection");
                }
            }
        }
        check(roster.size() == 35 && roster.get(1093) == 2, "35 distinct five-stars; Xianyun twice");
        for (var entry : roster.entrySet()) check(entry.getValue() == (entry.getKey() == 1093 ? 2 : 1), "Only Xianyun repeats");
        for (int index : new int[]{1, 7}) {
            var characters = phases.get(index).getAsJsonObject().getAsJsonArray("characters");
            check(characters.get(0).getAsJsonObject().get("card").getAsInt() == (index == 1 ? 1016 : 1026), "Diluc/Xiao lead their sets");
            check(characters.get(1).getAsJsonObject().get("card").getAsInt() == 1093, "Xianyun shares their weapon pair");
        }
    }
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[1]);
        Path root = Files.createTempDirectory(work, "rotation-check-");
        String catalog = Files.readString(work.resolve("rotation-catalog.json"));
        Files.writeString(root.resolve("rotation-catalog.json"), catalog);
        var baseline = GSON.fromJson(Files.readString(work.resolve("Banners.json")), JsonArray.class).get(0).getAsJsonObject();
        validateCatalog(GSON.fromJson(catalog, JsonObject.class), baseline);
        var clock = new TestClock(Instant.EPOCH); clock.day("2026-10-09");
        var store = new CatalogStore(root, clock);
        check(store.current(false).index() == 0, "Initial set");
        var next = store.current(true);
        check(next.index() == 1 && next.banners()[1].getRateUpItems5()[0] == 1016 && next.banners()[2].getRateUpItems5()[0] == 1093, "Next set is Diluc/Xianyun");
        check(store.current(true).index() == 2, "Manual advances");
        check(new CatalogStore(root, clock).current(false).index() == 2, "Restart persistence");
        clock.day("2026-10-12"); check(store.current(false).index() == 5, "Offline catch-up");
        check(store.current(false).index() == 5, "No repeated advance same day");
        clock.day("2026-10-21"); check(store.current(false).index() == 5, "Full nine-day cycle wraps");
        clock.day("2026-10-20"); check(store.current(false).day().equals(LocalDate.parse("2026-10-21")), "Backward clock does not roll back");
        writeState(root,8,"2026-10-08"); clock.day("2026-10-09");
        check(store.current(true).index() == 1, "Daily plus manual each advance once");
        for (String day : new String[]{"2026-03-29","2026-10-25"}) {
            var date = LocalDate.parse(day); writeState(root,0,date.minusDays(1).toString());
            clock.time.set(date.atStartOfDay(ZONE).minusNanos(1).toInstant());
            check(store.current(false).index() == 0, "No early DST rotation");
            clock.day(day); check(store.current(false).index() == 1, "Local midnight across DST");
        }
        writeState(root,0,"2026-10-09"); clock.day("2026-10-09");
        var threads = Executors.newFixedThreadPool(4);
        var futures = new ArrayList<Future<?>>();
        for (int i=0;i<40;i++) futures.add(threads.submit(() -> { try { store.current(true); } catch(Exception e) { throw new RuntimeException(e); } }));
        for (var future : futures) future.get(); threads.shutdown();
        check(store.current(false).index() == 40 % store.size(), "Concurrent advances conserved");
        Files.writeString(root.resolve("rotation-state.json"), "{broken");
        String broken = Files.readString(root.resolve("rotation-state.json"));
        try { store.current(true); throw new AssertionError("Corrupt state accepted"); } catch (JsonParseException expected) {}
        check(Files.readString(root.resolve("rotation-state.json")).equals(broken), "Corrupt state not overwritten");
        var invalid = GSON.fromJson(catalog,JsonObject.class);
        invalid.getAsJsonArray("phases").get(0).getAsJsonObject().getAsJsonArray("banners").remove(6);
        Files.writeString(root.resolve("rotation-catalog.json"),GSON.toJson(invalid));
        try { new CatalogStore(root,clock); throw new AssertionError("Invalid layout accepted"); } catch(java.io.IOException expected) {}
        Files.writeString(root.resolve("rotation-catalog.json"),catalog);
        System.out.println("PASS: nine themed sets, Diluc/Xiao with Xianyun, rates/pairs, midnight/DST, restart/catch-up, wrap, backward clock, 40 concurrent advances and rejected corrupt configuration.");
        if (args.length > 2 && args[2].equals("--store-only")) return;
        checkHooks(args[0]);
        System.setProperty("astaps.rotation.root",root.toString());
        System.setProperty("astaps.rotation.noTimer","true");
        String today=LocalDate.now(ZONE).toString(); writeState(root,0,today);
        var unsafeField=Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var unsafe=(Unsafe)unsafeField.get(null);
        var system=(GachaSystem)unsafe.allocateInstance(GachaSystem.class);
        var map=GachaSystem.class.getDeclaredField("gachaBanners"); map.setAccessible(true); map.set(system,new Int2ObjectOpenHashMap<GachaBanner>());
        Class.forName("emu.grasscutter.Grasscutter"); // Match the real startup order before DataLoader initializes FileUtils.
        system.load();
        check(system.getGachaBanners().size()==7 && system.getGachaBanners().get(7101).getRateUpItems5()[0]==1047,"Actual installed load preserves current Kazuha set");
        var server=(GameServer)unsafe.allocateInstance(GameServer.class);
        var gs=GameServer.class.getDeclaredField("gachaSystem"); gs.setAccessible(true); gs.set(server,system);
        var global=Grasscutter.class.getDeclaredField("gameServer"); global.setAccessible(true); global.set(null,server);
        var annotation=CatalogCommand.class.getAnnotation(Command.class);
        check(annotation.label().equals("catalog") && annotation.permission().equals("server.catalog"),"Command identity and permission");
        new CatalogCommand().execute(null,null,List.of("next"));
        check(system.getGachaBanners().get(7101).getRateUpItems5()[0]==1016 && system.getGachaBanners().get(7102).getRateUpItems5()[0]==1093,"Actual next command selects Diluc/Xianyun");
        check(Arrays.equals(system.getGachaBanners().get(7200).getRateUpItems5(),new int[]{12501,14515}),"Actual next command selects matching weapons");
        writeState(root,3,today); CatalogRotation.initialize(system);
        check(system.getGachaBanners().get(7101).getRateUpItems5()[0]==1052,"Actual reload applies persisted Raiden set");
        new CatalogCommand().execute(null,null,List.of("next"));
        check(system.getGachaBanners().get(7101).getRateUpItems5()[0]==1002,"Actual command advances to Ayaka set");
        new CatalogCommand().execute(null,null,List.of());
        check(system.getGachaBanners().get(7101).getRateUpItems5()[0]==1002,"Status does not advance");
        writeState(root,4,LocalDate.now(ZONE).minusDays(1).toString());
        CatalogRotation.refresh(system);
        check(system.getGachaBanners().get(7101).getRateUpItems5()[0]==1087,"Actual daily refresh advances to Neuvillette set");
        var old=system.getGachaBanners().get(7101);
        Files.delete(root.resolve("rotation-state.json")); Files.createDirectory(root.resolve("rotation-state.json"));
        try { CatalogRotation.command(system,true); throw new AssertionError("Invalid state path accepted"); } catch(java.io.IOException expected) {}
        check(system.getGachaBanners().get(7101)==old,"Failed persistence retains active set");
        System.out.println("PASS: actual installed load/refresh hooks, command annotation/execution, status and fail-safe retention; no accounts or wishes touched.");
    }
}
