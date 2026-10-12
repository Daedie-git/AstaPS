package emu.grasscutter.database;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import java.lang.reflect.Field;
import org.bson.types.ObjectId;

/** Resolves the collection and durable ID before a replacement is queued. */
final class PersistenceKey {
    private record Key(String collection, Object id) {}
    private static final Object ID_RESERVATION_LOCK = new Object();

    private static final ClassValue<Field> IDS = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            for (var current = type; current != null; current = current.getSuperclass()) {
                for (var field : current.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Id.class)) {
                        field.setAccessible(true);
                        return field;
                    }
                }
            }
            throw new IllegalArgumentException("No persistence ID: " + type.getName());
        }
    };

    static Object of(Object entity) {
        var field = IDS.get(entity.getClass());
        try {
            // Assigned durable IDs do not change. Taking the entity monitor just to read one
            // inverts the session-fence order against claim handlers that save under that monitor.
            Object id = field.get(entity);
            if (id == null && (field.getType() == ObjectId.class || field.getType() == String.class)) {
                synchronized (ID_RESERVATION_LOCK) {
                    id = field.get(entity);
                    if (id == null) {
                        var generated = new ObjectId();
                        id = field.getType() == String.class ? generated.toHexString() : generated;
                        field.set(entity, id);
                    }
                }
            }
            if (id == null) throw new IllegalArgumentException("Missing persistence ID");
            var annotation = field.getDeclaringClass().getAnnotation(Entity.class);
            var collection = annotation == null ? field.getDeclaringClass().getName() : annotation.value();
            return new Key(collection, id);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot resolve persistence ID", e);
        }
    }

    private PersistenceKey() {}
}
