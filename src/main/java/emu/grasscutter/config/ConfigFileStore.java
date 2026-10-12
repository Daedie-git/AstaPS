package emu.grasscutter.config;

import com.google.gson.JsonObject;
import emu.grasscutter.utils.JsonUtils;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static java.nio.file.StandardCopyOption.*;
import static java.nio.file.StandardOpenOption.*;

/** Startup-only configuration migration and atomic file replacement. */
public final class ConfigFileStore {
    private ConfigFileStore() {}

    public static ConfigContainer load(Path path) throws IOException {
        JsonObject json = JsonUtils.loadToClass(path, JsonObject.class);
        if (json == null) throw new IOException("Configuration must be an object");
        var config = JsonUtils.decode(json, ConfigContainer.class);
        if (config.server == null || config.account == null || config.folderStructure == null
                || config.server.game == null || config.server.http == null || config.language == null
                || config.databaseInfo == null || config.server.dispatch == null) {
            throw new IOException("Configuration sections cannot be null");
        }
        int version = json.has("version") ? json.get("version").getAsInt() : 0;
        if (version < 0 || version > ConfigContainer.currentVersion()) {
            throw new IOException("Configuration version is newer than this server");
        }
        if (version < ConfigContainer.currentVersion()) {
            ConfigContainer.migrate(config);
            save(path, config);
        }
        return config;
    }

    @FunctionalInterface
    interface Replacement { void replace(Path staged, Path target) throws IOException; }

    public static void save(Path path, ConfigContainer config) throws IOException {
        save(path, config, (staged, target) -> Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING));
    }

    static synchronized void save(Path path, ConfigContainer config, Replacement replacement) throws IOException {
        Path target = path.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".config-", ".json");
        try {
            Files.writeString(temporary, JsonUtils.encode(config), StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary, WRITE)) { channel.force(true); }
            if (Files.exists(target)) {
                Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"), REPLACE_EXISTING);
            }
            // Refuse a non-atomic fallback: failure leaves the original target intact.
            replacement.replace(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }
}
