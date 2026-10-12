package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.game.mail.Mail;
import org.junit.jupiter.api.Test;

class WriteOwnerTest {
    @Test
    void playerDocumentsResolveToTheirOwner() throws Exception {
        var mail = new Mail();
        var field = Mail.class.getDeclaredField("ownerUid");
        field.setAccessible(true);
        field.setInt(mail, 42);
        assertEquals(new DatabaseHelper.PlayerOwner(42), DatabaseHelper.ownerOf(mail));

    }

    @Test
    void unownedObjectsHaveNoOwner() {
        assertNull(DatabaseHelper.ownerOf(new Object()));
        assertNull(DatabaseHelper.ownerOf(new Mail()), "UID 0 is no player");
    }
}
