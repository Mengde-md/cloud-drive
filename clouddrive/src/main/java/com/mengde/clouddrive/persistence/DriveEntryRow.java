package com.mengde.clouddrive.persistence;

/**
 * drive_entries 表的行记录（Day07）。目录的 byteSize 为 null，文件为非负值。
 *
 * <p>特意不让领域对象（DriveEntry）直接当映射对象：领域对象有构造校验和行为，
 * 而数据库行只是数据。两者分开，脏数据在转换处就能被发现，而不是在领域对象里绕过校验。</p>
 */
public record DriveEntryRow(
        long id,
        long ownerId,
        Long parentId,
        String displayName,
        String kind,
        Long byteSize) {
}
