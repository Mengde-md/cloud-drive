package com.mengde.clouddrive.error;

public final class EntryNotFoundException extends RuntimeException {
    public EntryNotFoundException(long entryId) {
        super("entry not found: " + entryId);
    }
}
