package emu.grasscutter.server.game;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One account owner from token admission until its final writes are acknowledged. */
public final class AccountSessionLease {
    public record Lease(String accountId, long generation, Object owner) {}
    private static final ConcurrentMap<String, Lease> OWNERS = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATION = new AtomicLong();
    private AccountSessionLease() {}

    public static Lease claim(String accountId, Object owner) {
        return claim(accountId, owner, -1);
    }
    public static synchronized Lease claim(String accountId, Object owner, int capacity) {
        if (OWNERS.containsKey(accountId) || (capacity >= 0 && OWNERS.size() >= capacity)) return null;
        var lease = new Lease(accountId, GENERATION.incrementAndGet(), owner);
        OWNERS.put(accountId, lease);
        return lease;
    }
    public static Lease current(String accountId) { return OWNERS.get(accountId); }

    public static boolean owns(Lease lease) {
        return lease != null && OWNERS.get(lease.accountId()) == lease;
    }
    public static void release(Lease lease) {
        if (lease != null) OWNERS.remove(lease.accountId(), lease);
    }
}
