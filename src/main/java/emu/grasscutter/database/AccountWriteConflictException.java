package emu.grasscutter.database;

/** An acknowledged no-write CAS rejection; the caller must reload before retrying. */
final class AccountWriteConflictException extends IllegalStateException {
    AccountWriteConflictException() {
        super("Account changed or no longer exists; reload before retrying");
    }
}
