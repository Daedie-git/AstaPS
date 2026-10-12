import com.google.gson.*;
import emu.grasscutter.game.Account;
import emu.grasscutter.game.gacha.*;
import emu.grasscutter.game.player.Player;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import sun.misc.Unsafe;

/** Isolated storage and actual client-packet checks; no database, currency or inventory changes. */
public final class CheckRotation {
    private static final Gson GSON = new Gson();
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
    private static final class TestClock extends Clock {
        Instant time; final ZoneId zone;
        TestClock(String day) { zone=ZONE;day(day); }
        public ZoneId getZone() { return zone; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time; }
        void day(String day) { time=LocalDate.parse(day).atStartOfDay(ZONE).toInstant(); }
    }
    private static void writeState(Path root,int index,String day) throws Exception {
        Files.writeString(root.resolve("rotation-state.json"),"{\"index\":"+index+",\"day\":\""+day+"\"}");
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        var field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(((Unsafe)field.get(null)).allocateInstance(type));
    }
    private static Player player() throws Exception {
        var player=allocate(Player.class);var account=new Account();account.setSessionKey("fixture-not-a-token");player.setAccount(account);
        var field=Player.class.getDeclaredField("gachaInfo");field.setAccessible(true);field.set(player,new PlayerGachaInfo());return player;
    }
    private static void validateCatalog(JsonObject config,JsonObject standard) {
        var phases=config.getAsJsonArray("phases");check(phases.size()==18,"18-day catalog");
        int[] archons={1022,4106,1073,1089,1052,1030};
        for (int day=0;day<phases.size();day++) {
            var phase=phases.get(day).getAsJsonObject();var objects=phase.getAsJsonArray("banners");
            check(objects.get(0).equals(standard),"Standard unchanged");
            var banners=GSON.fromJson(objects,GachaBanner[].class);check(banners.length==7,"Seven slots");
            check(Arrays.stream(banners).mapToInt(GachaBanner::getScheduleId).distinct().count()==7,"Distinct schedules");
            var featured=new HashSet<Integer>();var expected=new int[5];
            for (int i=0;i<7;i++) {
                var b=banners[i];b.onLoad();b.ensureFallback4Pools();
                check(b.getScheduleId()==(i==0?200:i<6?7100+i:7300),"Stable schedule IDs");
                check(b.getCost(1).getId()==(i==0?224:223) && b.getCost(10).getCount()==10,"Fate costs");
                check(b.getEventChance(4)==50 && b.getEventChance(5)==50,"Existing featured chances");
                for (int pull=1;pull<=80;pull++) check(b.getWeight(5,pull)==(pull<=49?200:pull>=70?10000:200+(pull-49)*9800/21),"Five-star curve");
                if (i>0 && i<6) {
                    check(b.getGachaType()==400 && b.getRateUpItems5().length==1 && b.getRateUpItems4().length==3,"Character routing");
                    featured.add(b.getRateUpItems5()[0]);
                    expected[i-1]=phase.getAsJsonArray("characters").get(i-1).getAsJsonObject().get("weapon").getAsInt();
                }
            }
            check(featured.size()==5 && banners[1].getRateUpItems5()[0]==archons[day%6],"Distinct team and scheduled Archon");
            var weapon=banners[6];check(weapon.getGachaType()==500,"Chronicled routing");
            check(Arrays.equals(expected,weapon.getRateUpItems5()),"Five associated weapons in character-tab order");
            check(weapon.getFallbackItems5Pool1().length==0 && weapon.getFallbackItems4Pool1().length==0,"Weapon-only fallback");
            check(!weapon.isValidEpitomizedWish(4106) && !weapon.isValidEpitomizedWish(11501),"Reject character and inactive weapon choices");
            for (int id:expected) check(weapon.isValidEpitomizedWish(id),"Active weapon target");
        }
    }
    public static void main(String[] args) throws Exception {
        Path work=Path.of(args[1]),root=Files.createTempDirectory(work,"rotation-check-");
        String catalog=Files.readString(work.resolve("rotation-catalog.json"));Files.writeString(root.resolve("rotation-catalog.json"),catalog);
        var baseline=JsonParser.parseString(Files.readString(work.resolve("Banners.json"))).getAsJsonArray().get(0).getAsJsonObject();
        validateCatalog(GSON.fromJson(catalog,JsonObject.class),baseline);
        var clock=new TestClock("2026-10-10");var store=new CatalogStore(root,clock);
        check(store.current(false).index()==0,"Initial set");check(store.current(true).index()==1,"Manual next");
        check(new CatalogStore(root,clock).current(false).index()==1,"Restart persistence");
        clock.day("2026-10-13");check(store.current(false).index()==4,"Offline catch-up");check(store.current(false).index()==4,"No repeated advance");
        clock.day("2026-10-31");check(store.current(false).index()==4,"Full-cycle wrap");
        clock.day("2026-10-30");check(store.current(false).day().equals(LocalDate.parse("2026-10-31")),"Backward clock retention");
        writeState(root,17,"2026-10-09");clock.day("2026-10-10");check(store.current(true).index()==1,"Daily and manual advance once each");
        for (String day:new String[]{"2026-03-29","2026-10-25"}) {
            var date=LocalDate.parse(day);writeState(root,0,date.minusDays(1).toString());clock.time=date.atStartOfDay(ZONE).minusNanos(1).toInstant();
            check(store.current(false).index()==0,"No early DST advance");clock.day(day);check(store.current(false).index()==1,"Local midnight across DST");
        }
        Files.delete(root.resolve("rotation-state.json"));clock.day("2026-10-11");
        check(store.current(false).index()==1,"Missing state uses anchor date, so tomorrow is Mavuika");
        writeState(root,0,"2026-10-10");clock.day("2026-10-10");var threads=Executors.newFixedThreadPool(4);var futures=new ArrayList<Future<?>>();
        for (int i=0;i<40;i++) futures.add(threads.submit(()->{try {store.current(true);}catch(Exception e){throw new RuntimeException(e);}}));
        for (var future:futures) future.get();threads.shutdown();check(store.current(false).index()==40%18,"Concurrent advances conserved");
        Files.writeString(root.resolve("rotation-state.json"),"{broken");
        try {store.current(true);throw new AssertionError("Corrupt state accepted");}catch(JsonParseException expected){}
        check(Files.readString(root.resolve("rotation-state.json")).equals("{broken"),"Corrupt state retained");
        var invalid=GSON.fromJson(catalog,JsonObject.class);invalid.getAsJsonArray("phases").get(0).getAsJsonObject().getAsJsonArray("banners").remove(6);
        Files.writeString(root.resolve("rotation-catalog.json"),GSON.toJson(invalid));
        try {new CatalogStore(root,clock);throw new AssertionError("Invalid layout accepted");}catch(java.io.IOException expected){}
        Files.writeString(root.resolve("rotation-catalog.json"),catalog);
        System.out.println("PASS: 18 teams, five matching weapons, seven slots, rates/costs/targets, anchor/midnight/DST, persistence, wrap and concurrency.");
        if (args.length>2 && args[2].equals("--store-only")) return;
        System.setProperty("astaps.rotation.root",root.toString());System.setProperty("astaps.rotation.noTimer","true");
        String today=LocalDate.now(ZONE).toString();writeState(root,0,today);
        var system=allocate(GachaSystem.class);var map=GachaSystem.class.getDeclaredField("gachaBanners");map.setAccessible(true);map.set(system,new Int2ObjectOpenHashMap<GachaBanner>());
        // Seed obsolete weapon tabs to prove they are removed on catalog reload.
        var legacy=GSON.fromJson(catalog,JsonObject.class);
        for (var p:legacy.getAsJsonArray("phases")) {
            var list=p.getAsJsonObject().getAsJsonArray("banners");list.remove(5);
            for (int i=0;i<2;i++) {
                var b=list.get(5).getAsJsonObject().deepCopy();b.addProperty("bannerType","WEAPON");b.addProperty("gachaType",302);b.addProperty("scheduleId",7200+i);list.add(b);
            }
        }
        Files.writeString(root.resolve("rotation-catalog.json"),GSON.toJson(legacy));CatalogRotation.initialize(system);
        check(system.getGachaBanners().size()==8,"Legacy eight-slot set");
        Files.writeString(root.resolve("rotation-catalog.json"),catalog);CatalogRotation.initialize(system);
        check(system.getGachaBanners().size()==7 && !system.getGachaBanners().containsKey(7200) && !system.getGachaBanners().containsKey(7201),"Stale weapon slots removed");
        var player=player();check(system.toProto(player).getGachaInfoListCount()==7,"Client sees seven banners");
        CatalogRotation.command(system,true);check(system.getGachaBanners().get(7101).getRateUpItems5()[0]==4106,"Tomorrow is Mavuika");
        var weapon=system.getGachaBanners().get(7300);var info=player.getGachaInfo().getBannerInfo(weapon);info.setWishItemId(12514);
        system.toProto(player);system.toProto(player);check(info.getWishItemId()==12514,"Reopening Wishes retains chosen weapon");
        var proto=weapon.toProto(player);check(proto.getDisplayChronicle5ItemListCount()==5 && proto.getWishItemId()==12514,"Five client targets and chosen weapon");
        CatalogRotation.command(system,false);check(system.getGachaBanners().get(7101).getRateUpItems5()[0]==4106,"Status does not advance");
        CatalogRotation.command(system,true);check(system.getGachaBanners().get(7101).getRateUpItems5()[0]==1073,"Next day is Nahida");
        writeState(root,0,LocalDate.now(ZONE).minusDays(1).toString());CatalogRotation.refresh(system);check(system.getGachaBanners().get(7101).getRateUpItems5()[0]==4106,"Daily refresh");
        var old=system.getGachaBanners().get(7101);Files.delete(root.resolve("rotation-state.json"));Files.createDirectory(root.resolve("rotation-state.json"));
        try {CatalogRotation.command(system,true);throw new AssertionError("Invalid state path accepted");}catch(java.io.IOException expected){}
        check(system.getGachaBanners().get(7101)==old,"Failed persistence retains set");
        System.out.println("PASS: actual client packets, repeated weapon selection display, refresh, stale-slot removal and failure retention; no accounts or wishes touched.");
    }
}
