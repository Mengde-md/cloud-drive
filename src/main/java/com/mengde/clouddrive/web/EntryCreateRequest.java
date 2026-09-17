package com.mengde.clouddrive.web;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.domain.EntryKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Locale;

/**
 * 创建条目的 HTTP 请求体。本阶段由客户端声明条目 id：这与 Day03
 * “重复键是业务异常”的语义一致，服务端生成 id 要等数据库阶段（Day07+）。
 *
 * @param parentId    父目录 id，根条目传 null
 * @param displayName 显示名，不能为空白
 * @param kind        条目类型：DIRECTORY 或 DOCUMENT（大小写不敏感）
 * @param byteSize    文档字节数，仅 DOCUMENT 使用，不能为负
 */
public record EntryCreateRequest(
        @Positive(message = "parentId must be positive when present") Long parentId,
        @NotBlank(message = "displayName must not be blank") String displayName,
        @NotBlank(message = "kind must not be blank") String kind,
        @PositiveOrZero(message = "byteSize must not be negative") Long byteSize) {

    public DriveEntry toEntry(long id, long ownerId) {
        EntryKind entryKind = parseKind(kind);
        if (entryKind == EntryKind.DIRECTORY) {
            return new DirectoryEntry(id, ownerId, parentId, displayName);
        }
        return new DocumentEntry(id, ownerId, parentId, displayName, byteSize == null ? 0L : byteSize);
    }

    public EntryKind kindValue() {
        return parseKind(kind);
    }

    public long effectiveByteSize() {
        return kindValue() == EntryKind.DOCUMENT && byteSize != null ? byteSize : 0L;
    }

    private static EntryKind parseKind(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "DIRECTORY" -> EntryKind.DIRECTORY;
            case "DOCUMENT" -> EntryKind.DOCUMENT;
            default -> throw new IllegalArgumentException("kind must be DIRECTORY or DOCUMENT");
        };
    }
}
