package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.Account;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AccountUpdateServiceTest {
    @Test void staleLanguageMutationPreservesNewCredentialsBanAndPermissions() {
        var store = new S(); var cached = account(1L, "old-token");
        assertDoesNotThrow(() -> AccountUpdateService.update(cached, a -> { a.setLocale(Locale.FRENCH); return null; }, store));
        assertEquals(Locale.FRENCH, store.persisted.getLocale());
        assertEquals("new-token", store.persisted.getToken()); assertEquals("new-password", store.persisted.getPassword());
        assertEquals("new-token", cached.getToken()); assertEquals(store.persisted.getPersistenceVersion(), cached.getPersistenceVersion());
        assertEquals(store.persisted.getPermissions(), cached.getPermissions());
        assertEquals("new-ban", cached.getBanReason());
    }
    @Test void racingCredentialRotationRetriesOnlyTheRequestedMutation() {
        var store = new S(); store.race = true;
        var cached = account(1L, "old-token");
        boolean applied = AccountUpdateService.update(cached, a -> a.addPermission("example.new"), store);
        assertTrue(applied);
        assertEquals(2, store.loads);
        assertEquals("rotated-again", store.persisted.getToken());
        assertTrue(store.persisted.getPermissions().contains("example.existing"));
        assertTrue(cached.getPermissions().contains("example.new"));
    }
    @Test void repeatedConflictsAreBoundedAndCannotClaimSuccess() {
        var store = new S(); store.alwaysConflict = true;
        var cached = account(1L, "old-token");
        assertThrows(dev.morphia.VersionMismatchException.class,
                () -> AccountUpdateService.update(cached, a -> { a.setBanReason("explicit-ban"); return null; }, store));
        assertEquals(3, store.loads); assertEquals("old-token", cached.getToken());
    }
    static Account account(Long version, String token) {
        var a = new Account(); a.setId("test"); a.setPersistenceVersion(version); a.setToken(token); return a;
    }
    static Account copy(Account a) {
        var b = account(a.getPersistenceVersion(), a.getToken()); b.setPassword(a.getPassword()); b.setBanReason(a.getBanReason());
        b.setLocale(a.getLocale()); b.getPermissions().addAll(a.getPermissions()); return b;
    }
    static class S implements AccountUpdateService.Store {
        Account persisted = account(2L, "new-token"); boolean race, alwaysConflict; int loads;
        S() { persisted.setPassword("new-password"); persisted.setBanReason("new-ban"); persisted.addPermission("example.existing"); }
        public Account load(String id) { loads++; return copy(persisted); }
        public void save(Account a) {
            if (race) { persisted.setToken("rotated-again"); persisted.setPersistenceVersion(3L); race = false; }
            if (alwaysConflict || !java.util.Objects.equals(a.getPersistenceVersion(), persisted.getPersistenceVersion()))
                throw new dev.morphia.VersionMismatchException(Account.class, "test");
            a.setPersistenceVersion(a.getPersistenceVersion() + 1); persisted = copy(a);
        }
    }
}
