package emu.grasscutter.database;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.BiConsumer;

/** Optional consumer ownership policy; the writer provider does not depend on login. */
public final class PersistenceWriteFence {
    private record Policy(Predicate<Object> execution, BiConsumer<Object, Object> bind,
                          BiConsumer<Object, Runnable> admit) {}
    private static volatile Policy policy = new Policy(object -> true, (object, owner) -> {},
            (object, write) -> write.run());
    private PersistenceWriteFence() {}
    public static void install(Predicate<Object> execution, BiConsumer<Object, Object> bind,
            BiConsumer<Object, Runnable> admit) {
        policy = new Policy(Objects.requireNonNull(execution), Objects.requireNonNull(bind),
                Objects.requireNonNull(admit));
    }
    static <T> T bindOwner(T object, Object owner) {
        if (object != null) policy.bind().accept(object, owner);
        return object;
    }
    static void submit(Object object, Runnable write) { policy.admit().accept(object, write); }
    static boolean mayExecute(Object object) { return policy.execution().test(object); }
}
