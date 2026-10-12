package emu.grasscutter.game.inventory;

import java.util.*;

/** Session-local exclusion, including synchronous callbacks. Exceptions retain quarantine. */
public final class ClaimBoundary {
    private static final Map<Object, Set<String>> active = new WeakHashMap<>();

    private ClaimBoundary() {}

    public static synchronized Reservation reserve(Object owner, Collection<String> keys) {
        var held = active.computeIfAbsent(owner, unused -> new HashSet<>());
        if (keys.isEmpty() || keys.stream().anyMatch(held::contains)) return null;
        held.addAll(keys);
        return new Reservation(owner, List.copyOf(keys));
    }

    public static Reservation reserve(Object owner, String key) {
        return reserve(owner, List.of(key));
    }

    public static final class Reservation {
        private final Object owner;
        private final List<String> keys;

        private Reservation(Object owner, List<String> keys) {
            this.owner = owner;
            this.keys = keys;
        }

        /** Only after a clean rejection or after installing the successful replay marker. */
        public void release() {
            synchronized (ClaimBoundary.class) {
                var held = active.get(owner);
                if (held != null) {
                    held.removeAll(keys);
                    if (held.isEmpty()) active.remove(owner);
                }
            }
        }
    }
}
