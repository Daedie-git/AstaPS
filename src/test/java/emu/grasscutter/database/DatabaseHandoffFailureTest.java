package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import dev.morphia.Datastore;
import emu.grasscutter.Grasscutter;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class DatabaseHandoffFailureTest {
    @Test
    void replacementFailureCannotBecomeSuccessfulHandoff() throws Exception {
        var datastore = (Datastore) Proxy.newProxyInstance(Datastore.class.getClassLoader(),
                new Class<?>[] {Datastore.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("injected persistence failure");
                });
        var field = DatabaseManager.class.getDeclaredField("gameDatastore");
        var mode = Grasscutter.class.getDeclaredField("runModeOverride");
        field.setAccessible(true);
        mode.setAccessible(true);
        var failures = new IsolatedWriterFailures();
        var previous = field.get(null);
        var previousMode = mode.get(null);
        field.set(null, datastore);
        Grasscutter.setRunModeOverride(Grasscutter.ServerRunMode.HYBRID);
        try {
            DatabaseHelper.saveGameAsync(new ReplacementWriteOrderingTest.Snapshot(1));
            assertThrows(CompletionException.class, () -> DatabaseHelper.flushForLoginHandoff().join());
        } finally {
            try (var ignored = DatabaseWriteBarrier.acquire()) {
                // Wait without asserting acknowledgement so datastore restoration cannot race workers.
            } finally {
                failures.close();
                field.set(null, previous);
                mode.set(null, previousMode);
            }
        }
    }
}
