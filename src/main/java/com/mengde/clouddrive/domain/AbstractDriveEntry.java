package com.mengde.clouddrive.domain;

import java.util.Objects;

public abstract class AbstractDriveEntry implements DriveEntry {

    private final long id;
    private final long ownerId;
    private final Long parentId;
    private String displayName;

    protected AbstractDriveEntry(long id, long ownerId, Long parentId, String displayName) {
        this.id = requirePositive(id, "id");
        this.ownerId = requirePositive(ownerId, "ownerId");
        this.parentId = parentId == null ? null : requirePositive(parentId, "parentId");
        this.displayName = normalizeDisplayName(displayName);
    }

    @Override
    public final long id() {
        return id;
    }

    @Override
    public final long ownerId() {
        return ownerId;
    }

    @Override
    public final Long parentId() {
        return parentId;
    }

    @Override
    public final String displayName() {
        return displayName;
    }

    @Override
    public final void rename(String requestedName) {
        displayName = normalizeDisplayName(requestedName);
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static String normalizeDisplayName(String requestedName) {
        String normalizedName = Objects.requireNonNull(requestedName, "displayName").trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        return normalizedName;
    }
}
