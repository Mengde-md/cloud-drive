package com.mengde.clouddrive.web;

import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;

/**
 * 条目的 HTTP 响应体。目录条目的 byteSize 为 null，文档条目返回真实字节数。
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
