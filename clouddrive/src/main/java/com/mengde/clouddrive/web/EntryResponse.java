package com.mengde.clouddrive.web;

import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;

/**
 * 条目响应体（Day06）。目录的 byteSize 为 null。
 *
 * <p>它同时是 Day20 缓存里存的 JSON 结构，所以字段改动会让旧缓存反序列化失败——
 * 缓存代码对此做了兜底：解析失败就删键回源。</p>
 */
public record EntryResponse(
        long id,
        long ownerId,
        Long parentId,
        String displayName,
        String kind,
        Long byteSize) {

    public static EntryResponse from(DriveEntry entry) {
        Long byteSize = entry instanceof DocumentEntry document ? document.byteSize() : null;
        return new EntryResponse(
                entry.id(),
                entry.ownerId(),
                entry.parentId(),
                entry.displayName(),
                entry.kind().name(),
                byteSize);
    }
}
