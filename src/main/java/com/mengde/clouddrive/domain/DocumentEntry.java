package com.mengde.clouddrive.domain;

public final class DocumentEntry extends AbstractDriveEntry {

    private final long byteSize;

    public DocumentEntry(long id, long ownerId, Long parentId, String displayName, long byteSize) {
        super(id, ownerId, parentId, displayName);
        if (byteSize < 0) {
            throw new IllegalArgumentException("byteSize must not be negative");
        }
        this.byteSize = byteSize;
    }

    public long byteSize() {
        return byteSize;
    }

    @Override
    public EntryKind kind() {
        return EntryKind.DOCUMENT;
    }
}
