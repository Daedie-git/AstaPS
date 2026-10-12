package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;
import emu.grasscutter.game.Account;
import org.junit.jupiter.api.Test;

class IpBanProvenanceTest {
    static Account banned(String provenance, String reason) {
        var account = new Account();
        account.setBanned(true);
        account.setBannedByIp(provenance);
        account.setBanReason(reason);
        account.setBanStartTime(1);
        account.setBanEndTime(Integer.MAX_VALUE);
        return account;
    }
    @Test void legacyReasonOnlyBanClearsAllFields() {
        var account = banned(null, "IP已封禁: 192.0.2.1");
        assertTrue(DatabaseHelper.clearBanForIp(account, "192.0.2.1"));
        assertFalse(account.isBanned());
        assertNull(account.getBanReason());
        assertNull(account.getBannedByIp());
        assertEquals(0, account.getBanStartTime());
        assertEquals(0, account.getBanEndTime());
        assertFalse(DatabaseHelper.clearBanForIp(account, "192.0.2.1"));
    }
    @Test void legacySuffixHasAnExactIpBoundary() {
        assertTrue(DatabaseHelper.clearBanForIp(banned(null, "IP已封禁: 192.0.2.1 (reason)"), "192.0.2.1"));
        assertFalse(DatabaseHelper.clearBanForIp(banned(null, "IP已封禁: 192.0.2.10"), "192.0.2.1"));
    }
    @Test void manualOrDifferentIpBanCannotBeClearedByLegacyReason() {
        assertFalse(DatabaseHelper.clearBanForIp(banned(null, "manual ban"), "192.0.2.1"));
        assertFalse(DatabaseHelper.clearBanForIp(banned("192.0.2.2", "IP已封禁: 192.0.2.1"), "192.0.2.1"));
        assertTrue(DatabaseHelper.clearBanForIp(banned("192.0.2.1", "reason"), "192.0.2.1"));
    }
    @Test void conflictReloadRevalidatesProvenanceAndDoesNotClearNewManualBan() {
        var legacy = banned(null, "IP已封禁: 192.0.2.1"); legacy.setId("legacy");
        var manual = banned(null, "new manual ban"); manual.setId("legacy");
        var store = new AccountUpdateService.Store() {
            int loads;
            public Account load(String id) { return ++loads == 1 ? legacy : manual; }
            public void save(Account a) {
                if (a == legacy) throw new dev.morphia.VersionMismatchException(Account.class, "legacy");
            }
        };
        var cached = new Account(); cached.setId("legacy");
        boolean cleared = AccountUpdateService.update(cached, a -> DatabaseHelper.clearBanForIp(a, "192.0.2.1"), store);
        assertFalse(cleared);
        assertTrue(manual.isBanned());
        assertEquals("new manual ban", cached.getBanReason());
    }
}
