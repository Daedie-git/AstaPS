package emu.grasscutter.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.MongoClientSettings;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.MapperOptions;
import emu.grasscutter.game.player.Player;
import org.junit.jupiter.api.Test;

class PlayerRuntimeMappingAuditTest {
    @Test void departureCompletionIsExcludedFromActualMorphiaModel() {
        // The datastore proxy permits model/codec access only; no MongoClient is constructed.
        var reference = new java.util.concurrent.atomic.AtomicReference<Mapper>();
        var datastore = (dev.morphia.Datastore) java.lang.reflect.Proxy.newProxyInstance(
                dev.morphia.Datastore.class.getClassLoader(), new Class<?>[]{dev.morphia.Datastore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMapper" -> reference.get();
                    case "getCodecRegistry" -> reference.get().getCodecRegistry();
                    default -> throw new AssertionError("Unexpected datastore call: " + method.getName());
                });
        var mapper = new Mapper(datastore, MongoClientSettings.getDefaultCodecRegistry(), MapperOptions.DEFAULT);
        reference.set(mapper);
        var model = mapper.getEntityModel(Player.class);
        assertNotNull(model.getIdProperty());
        assertNotNull(model.getProperty("nickname"));
        assertNull(model.getProperty("scriptDepartureCompletion"));
        assertNull(model.getProperty("queuedTeleport"));
        assertNull(model.getProperty("scene"));
        assertNull(model.getProperty("world"));
        assertTrue(model.getProperties().stream().noneMatch(property ->
                java.util.concurrent.Future.class.isAssignableFrom(property.getType())));
        assertFalse(mapper.isMappable(emu.grasscutter.scripts.SceneScriptManager.class));
    }
}
