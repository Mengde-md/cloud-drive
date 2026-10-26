package com.mengde.clouddrive.domain;

/**
 * 目录条目（Day02）。没有字节数：目录的"大小"是派生信息，不是自身状态，
 * 提前放一个 byteSize 字段进来只会让"目录的大小是多少"变成一个永远要同步的谎言。
 */
public final class DirectoryEntry extends AbstractDriveEntry {

    public DirectoryEntry(long id, long ownerId, Long parentId, String displayName) {
        super(id, ownerId, parentId, displayName);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.DIRECTORY;
    }
}
