package com.mengde.clouddrive.persistence;

/**
 * 数据库行记录，与 {@code drive_entries} 表一一对应。
 * 目录条目的 byteSize 为 null；文档条目的 byteSize 为非负值。
 *
 * @param id          条目 id（本阶段由客户端声明，与 Day03 语义一致）
 * @param ownerId     归属用户 id
 * @param parentId    父目录 id，根条目为 null
 * @param displayName 显示名
 * @param kind        条目类型：DIRECTORY 或 DOCUMENT
 * @param byteSize    文档字节数，目录为 null
 */
public record DriveEntryRow(
        long id,
        long ownerId,
        Long parentId,
        String displayName,
        String kind,
        Long byteSize) {
}
