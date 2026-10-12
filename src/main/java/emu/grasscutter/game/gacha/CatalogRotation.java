package emu.grasscutter.game.gacha;

import emu.grasscutter.Grasscutter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Apply the durable catalog without touching player records. */
public final class CatalogRotation {
    private static CatalogStore store;
    private static CatalogStore.Snapshot installed;
    private static boolean timerStarted;

    private CatalogRotation() {}

    public static void initialize(GachaSystem system) {
        synchronized (system) {
            try {
                var replacement = new CatalogStore(Path.of(System.getProperty("astaps.rotation.root", "customization")), Clock.systemUTC());
                var snapshot = replacement.current(false);
                store = replacement;
                apply(system, snapshot);
                if (!timerStarted && !Boolean.getBoolean("astaps.rotation.noTimer")) {
                    timerStarted = true;
                    var timer = Executors.newSingleThreadScheduledExecutor(r -> {
                        var thread = new Thread(r, "banner-catalog-rotation");
                        thread.setDaemon(true);
                        return thread;
                    });
                    timer.scheduleAtFixedRate(() -> refresh(system), 30, 30, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                Grasscutter.getLogger().error("[Catalog] Initialization failed; retaining loaded banners", e);
            }
        }
    }

    public static void refresh(GachaSystem system) {
        synchronized (system) {
            if (store == null) {
                initialize(system);
                return;
            }
            try {
                var snapshot = store.current(false);
                if (installed == null || snapshot.index() != installed.index() || !snapshot.day().equals(installed.day()))
                    apply(system, snapshot);
            } catch (Exception e) {
                Grasscutter.getLogger().error("[Catalog] Daily refresh failed; retaining active banners", e);
            }
        }
    }

    public static String command(GachaSystem system, boolean next) throws IOException {
        synchronized (system) {
            if (store == null) initialize(system);
            if (store == null) throw new IOException("Catalog unavailable; check server logs");
            var snapshot = store.current(next);
            apply(system, snapshot);
            return "Catalog " + (snapshot.index() + 1) + "/" + store.size() + ": " + snapshot.name()
                    + ". Daily at 00:00 Europe/Amsterdam. Close and reopen Wishes to refresh.";
        }
    }

    private static void apply(GachaSystem system, CatalogStore.Snapshot snapshot) {
        // Stable IDs keep existing character/weapon pity routing; no player state is modified.
        if (installed != null) {
            for (var previous : installed.banners()) system.getGachaBanners().remove(previous.getScheduleId());
        }
        for (var banner : snapshot.banners()) system.getGachaBanners().put(banner.getScheduleId(), banner);
        installed = snapshot;
        Grasscutter.getLogger().info("[Catalog] Active set {}: {}", snapshot.index() + 1, snapshot.name());
    }
}
