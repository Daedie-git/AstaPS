package emu.grasscutter.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileStoreTest {
    @TempDir Path directory;
    @Test void realStartupMigrationKeepsValuesAndBacksUpExactOriginal() throws Exception {
        var path = directory.resolve("config.json");
        String legacy = "{\"server\":{\"game\":{\"bindPort\":12345}},\"account\":{\"autoCreate\":true}}";
        Files.writeString(path, legacy);
        var loaded = ConfigFileStore.load(path);
        assertEquals(12345, loaded.server.game.bindPort); assertTrue(loaded.account.autoCreate);
        assertEquals(ConfigContainer.currentVersion(), loaded.version);
        assertEquals(legacy, Files.readString(directory.resolve("config.json.bak")));
        assertEquals(12345, ConfigFileStore.load(path).server.game.bindPort);
    }
    @Test void failedAtomicReplacementLeavesOriginalAndRemovesStagingFile() throws Exception {
        var path = directory.resolve("config.json"); Files.writeString(path, "original fixture");
        assertThrows(IOException.class, () -> ConfigFileStore.save(path, new ConfigContainer(),
                (stage, target) -> { throw new IOException("Injected replacement failure"); }));
        assertEquals("original fixture", Files.readString(path));
        assertEquals("original fixture", Files.readString(directory.resolve("config.json.bak")));
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith(".config-")));
        }
    }
    @Test void invalidConfigIsNotMigratedOrOverwritten() throws Exception {
        var path = directory.resolve("config.json"); String invalid = "{\"server\":null}";
        Files.writeString(path, invalid);
        assertThrows(IOException.class, () -> ConfigFileStore.load(path));
        assertEquals(invalid, Files.readString(path));
    }
}
