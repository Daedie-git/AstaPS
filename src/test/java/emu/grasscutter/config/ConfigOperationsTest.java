package emu.grasscutter.config;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.utils.JsonUtils;
import java.nio.file.*;
import org.junit.jupiter.api.*;

/** Only the test JVM's generated worktree configuration is used; no installed configuration. */
class ConfigOperationsTest {
    @Test void versionlessMigrationPreservesRecognizedValues() throws Exception {
        var original = Grasscutter.config;
        try {
            var synthetic = new ConfigContainer(); synthetic.server.game.bindPort = 12345;
            synthetic.account.autoCreate = true;
            var json = JsonUtils.toJson(synthetic).getAsJsonObject(); json.remove("version");
            Files.writeString(Grasscutter.configFile.toPath(), json.toString());
            // Original updateConfig assumes parsing has already happened.
            Grasscutter.config = JsonUtils.decode(json, ConfigContainer.class);
            ConfigContainer.updateConfig();
            var saved = JsonUtils.loadToClass(Grasscutter.configFile.toPath(), ConfigContainer.class);
            assertEquals(12345, saved.server.game.bindPort);
            assertTrue(saved.account.autoCreate);
        } finally {
            Grasscutter.config = original; Grasscutter.saveConfig(original);
        }
    }
    @Test void savingFailureIsReportedNotSwallowed() throws Exception {
        var path = Grasscutter.configFile.toPath();
        var original = Grasscutter.config;
        Files.delete(path); Files.createDirectory(path);
        try {
            assertThrows(RuntimeException.class, () -> Grasscutter.saveConfig(new ConfigContainer()));
        } finally {
            Files.delete(path); Grasscutter.saveConfig(original);
        }
    }
    @Test void configReloadIsExplicitlyRestartOnly() {
        var original = Grasscutter.config;
        assertThrows(IllegalStateException.class, Grasscutter::loadConfig);
        assertSame(original, Grasscutter.config);
    }
}
