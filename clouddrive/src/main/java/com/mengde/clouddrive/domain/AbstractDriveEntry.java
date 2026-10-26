package com.mengde.clouddrive.domain;

import java.util.Objects;

/**
 * 目录与文件的共享状态与共享校验（Day02），以及领域身份语义（Day03）。
 *
 * <p>构造函数就是这个类的护栏：id/ownerId 必须为正、parentId 若存在必须为正、
 * displayName 去空白后不得为空。对象一旦构造成功，后续代码就不需要再重复这些判断。</p>
 *
 * <p>equals/hashCode 用 (ownerId, id) 而不是全部字段：领域里"同一个条目"的含义是
 * 同一个人的同一个 id，而不是"显示名也一样"。重命名不应该改变身份，
 * 所以 displayName 这种可变字段绝不能参与 hashCode，否则放进 HashSet 后改名字就再也找不回来。
 * 两个方法都声明为 final，避免子类改写后破坏这个不变量。</p>
 */
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

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DriveEntry entry)) {
            return false;
        }
        return id == entry.id() && ownerId == entry.ownerId();
    }

    @Override
    public final int hashCode() {
        return Objects.hash(ownerId, id);
    }

    @Override
    public String toString() {
        return kind() + "{id=" + id + ", ownerId=" + ownerId
                + ", parentId=" + parentId + ", displayName='" + displayName + "'}";
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
