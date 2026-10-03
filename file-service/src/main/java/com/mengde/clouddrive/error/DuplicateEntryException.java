package com.mengde.clouddrive.error;

public final class DuplicateEntryException extends RuntimeException {
    public DuplicateEntryException(long entryId) {
        super("entry already exists: " + entryId);
    }
}
