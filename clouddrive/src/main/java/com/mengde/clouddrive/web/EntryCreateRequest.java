package com.mengde.clouddrive.web;

import com.mengde.clouddrive.domain.EntryKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Locale;

/**
 * 创建条目的请求体（Day06 定义，Day16 收紧）。
 *
 * <p>注意这里<b>没有 id，也没有 ownerId</b>：</p>
 * <ul>
 *   <li>id 由数据库自增生成，客户端自带 id 会在并发下撞主键；</li>
 *   <li>ownerId 只能来自 JWT。如果从请求体里读 ownerId，
 *       任何人改一个数字就能往别人的网盘里塞文件。</li>
 * </ul>
 */
public record EntryCreateRequest(
        @Positive(message = "parentId must be positive when present") Long parentId,
        @NotBlank(message = "displayName must not be blank") String displayName,
        @NotBlank(message = "kind must not be blank") String kind,
        @PositiveOrZero(message = "byteSize must not be negative") Long byteSize) {

    public EntryKind kindValue() {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        return switch (kind.trim().toUpperCase(Locale.ROOT)) {
            case "DIRECTORY" -> EntryKind.DIRECTORY;
            case "DOCUMENT" -> EntryKind.DOCUMENT;
            default -> throw new IllegalArgumentException("kind must be DIRECTORY or DOCUMENT");
        };
    }

    /** 目录不占字节数；文档缺省按 0 处理。 */
    public long effectiveByteSize() {
        if (kindValue() == EntryKind.DIRECTORY) {
            return 0L;
        }
        return byteSize == null ? 0L : byteSize;
    }
}
