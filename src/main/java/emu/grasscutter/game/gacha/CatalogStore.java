package emu.grasscutter.game.gacha;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/** Durable daily catalog position; storage and time are injectable for offline tests. */
public final class CatalogStore {
    private final Path statePath;
    private final Clock clock;
    private final ZoneId zone;
    private final JsonArray phases;
    private final Gson gson = new Gson();

    public record Snapshot(int index, LocalDate day, String name, GachaBanner[] banners) {}

    public CatalogStore(Path root, Clock clock) throws IOException {
        this.statePath = root.resolve("rotation-state.json");
        this.clock = clock;
        var config = gson.fromJson(Files.readString(root.resolve("rotation-catalog.json")), JsonObject.class);
        this.zone = ZoneId.of(config.get("timezone").getAsString());
        this.phases = config.getAsJsonArray("phases");
        if (phases == null || phases.isEmpty()) throw new IOException("Empty banner catalog");
        for (int i = 0; i < phases.size(); i++) parse(i);
    }

    public int size() {
        return phases.size();
    }

    public synchronized Snapshot current(boolean advance) throws IOException {
        var today = LocalDate.now(clock.withZone(zone));
        boolean missing = !Files.exists(statePath);
        int index = 0;
        var day = today;
        if (!missing) {
            var state = gson.fromJson(Files.readString(statePath), JsonObject.class);
            index = state.get("index").getAsInt();
            day = LocalDate.parse(state.get("day").getAsString());
            if (index < 0 || index >= phases.size()) throw new IOException("Invalid catalog index");
        }
        long elapsed = Math.max(0, ChronoUnit.DAYS.between(day, today));
        int next = (int) ((index + elapsed % phases.size() + (advance ? 1 : 0)) % phases.size());
        var nextDay = elapsed > 0 ? today : day;
        var banners = parse(next);
        if (missing || elapsed > 0 || advance) save(next, nextDay);
        return new Snapshot(next, nextDay, phases.get(next).getAsJsonObject().get("name").getAsString(), banners);
    }

    private GachaBanner[] parse(int index) throws IOException {
        var phase = phases.get(index).getAsJsonObject();
        var banners = gson.fromJson(phase.get("banners"), GachaBanner[].class);
        if (banners == null || banners.length != 7 || Arrays.stream(banners).mapToInt(GachaBanner::getScheduleId).distinct().count() != 7)
            throw new IOException("Catalog must have seven distinct schedules");
        int standard = 0, characters = 0, weapons = 0;
        for (var b : banners) {
            b.onLoad();
            b.ensureFallback4Pools();
            if (b.isDisabled() || b.isDeprecated()) throw new IOException("Inactive catalog banner");
            switch (b.getBannerType()) {
                case STANDARD -> standard++;
                case CHARACTER2 -> characters++;
                case WEAPON -> weapons++;
                default -> throw new IOException("Unexpected catalog banner type");
            }
        }
        if (standard != 1 || characters != 4 || weapons != 2) throw new IOException("Invalid catalog layout");
        return banners;
    }

    private void save(int index, LocalDate day) throws IOException {
        var state = new JsonObject();
        state.addProperty("index", index);
        state.addProperty("day", day.toString());
        var temporary = Files.createTempFile(statePath.getParent(), "rotation-state-", ".json");
        try {
            Files.writeString(temporary, gson.toJson(state) + "\n");
            Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
