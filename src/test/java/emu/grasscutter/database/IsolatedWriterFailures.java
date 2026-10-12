package emu.grasscutter.database;

/** Restores the test JVM's global writer failure ledger after deliberately injecting failures. */
final class IsolatedWriterFailures implements AutoCloseable {
    private final java.lang.reflect.Field field;
    private final Object previous;
    private final java.util.Map<Object, Throwable> keyed;
    private final java.util.Map<Object, Throwable> previousKeyed;

    IsolatedWriterFailures() throws Exception {
        try (var ignored = DatabaseWriteBarrier.acquire()) {
            field = DatabaseWriterManager.class.getDeclaredField("firstWriteFailure");
            field.setAccessible(true);
            previous = field.get(DatabaseHelper.getWriterManager());
            field.set(DatabaseHelper.getWriterManager(), null);
            var keyedField = DatabaseWriterManager.class.getDeclaredField("keyedWriteFailures");
            keyedField.setAccessible(true);
            keyed = (java.util.Map<Object, Throwable>) keyedField.get(DatabaseHelper.getWriterManager());
            previousKeyed = new java.util.HashMap<>(keyed);
            keyed.clear();
        }
    }

    @Override
    public void close() throws Exception {
        try (var ignored = DatabaseWriteBarrier.acquire()) {
            field.set(DatabaseHelper.getWriterManager(), previous);
            keyed.clear();
            keyed.putAll(previousKeyed);
        }
    }
}
