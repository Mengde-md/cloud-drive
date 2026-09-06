package com.mengde.clouddrive.domain;

public final class DirectoryEntry extends AbstractDriveEntry {

    public DirectoryEntry(long id, long ownerId, Long parentId, String displayName) {
        super(id, ownerId, parentId, displayName);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.DIRECTORY;
    }
}
