package emu.grasscutter.game.gacha;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BatchAvatarOwnershipTest {
    @Test
    void repeatedNewCopiesAreNewOnlyOnce() {
        var batch = new BatchAvatarOwnership(id -> -1);
        assertEquals(-1, batch.beforeCopy(4101));
        assertEquals(0, batch.beforeCopy(4101));
        assertEquals(1, batch.beforeCopy(4101));
    }

    @Test
    void copiesCrossC6WithinTheBatch() {
        var batch = new BatchAvatarOwnership(id -> 4);
        assertEquals(4, batch.beforeCopy(4101));
        assertEquals(5, batch.beforeCopy(4101));
        assertEquals(6, batch.beforeCopy(4101));
    }

    @Test
    void weaponAndOtherAvatarStateRemainIndependent() {
        var batch = new BatchAvatarOwnership(id -> id == 100 ? -2 : -1);
        assertEquals(-2, batch.beforeCopy(100));
        assertEquals(-2, batch.beforeCopy(100));
        assertEquals(-1, batch.beforeCopy(4101));
        assertEquals(-1, batch.beforeCopy(4102));
    }
}
