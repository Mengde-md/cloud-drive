package com.mengde.clouddrive.domain;

/**
 * 文件条目（Day02）。byteSize 允许为 0（空文件是合法的），但不允许为负。
 */
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
