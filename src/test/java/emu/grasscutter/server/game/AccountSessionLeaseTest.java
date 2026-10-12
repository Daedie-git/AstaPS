package emu.grasscutter.server.game;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AccountSessionLeaseTest {
    @Test void pendingOwnerExcludesSecondAndStaleReleaseCannotRemoveReplacement() {
        var first = AccountSessionLease.claim("isolated-test-account", new Object());
        try {
            assertNotNull(first);
            assertNull(AccountSessionLease.claim(first.accountId(), new Object()));
            AccountSessionLease.release(first);
            var second = AccountSessionLease.claim(first.accountId(), new Object());
            try {
                assertTrue(second.generation() > first.generation());
                AccountSessionLease.release(first);
                assertTrue(AccountSessionLease.owns(second));
                assertFalse(AccountSessionLease.owns(first));
            } finally { AccountSessionLease.release(second); }
        } finally { AccountSessionLease.release(first); }
    }
}
