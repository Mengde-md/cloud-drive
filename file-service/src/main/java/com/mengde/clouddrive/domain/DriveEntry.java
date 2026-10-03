package com.mengde.clouddrive.domain;

public interface DriveEntry {

    long id();

    long ownerId();

    Long parentId();

    String displayName();

    EntryKind kind();

    void rename(String requestedName);
}
