package com.mengde.clouddrive.error;

public final class DuplicateEntryNameException extends RuntimeException {
    public DuplicateEntryNameException(String displayName) {
        super("an entry named '%s' already exists in this directory".formatted(displayName));
    }
}
