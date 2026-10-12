package emu.grasscutter.database;

import emu.grasscutter.game.Account;
import java.util.function.Function;

/** Applies an explicit administrative mutation without replacing unrelated account fields. */
final class AccountUpdateService {
    interface Store {
        Account load(String id);
        void save(Account account);
    }

    static <T> T update(Account cached, Function<Account, T> mutation, Store store) {
        synchronized (cached) {
            for (int attempt = 0; attempt < 3; attempt++) {
                var fresh = store.load(cached.getId());
                if (fresh == null) throw new IllegalStateException("Account no longer exists");
                T result = mutation.apply(fresh);
                if (!java.util.Objects.equals(cached.getId(), fresh.getId()))
                    throw new IllegalArgumentException("Account mutation cannot change identity");
                try {
                    store.save(fresh);
                } catch (dev.morphia.VersionMismatchException | AccountWriteConflictException conflict) {
                    if (attempt == 2) throw conflict;
                    continue;
                }
                cached.refreshPersistedState(fresh);
                return result;
            }
            throw new IllegalStateException("Account update attempts exhausted");
        }
    }

    private AccountUpdateService() {}
}
