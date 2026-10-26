package com.mengde.clouddrive.web;

import com.mengde.clouddrive.domain.EntryKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Day06/Day16：请求体到领域概念的转换边界。
 */
class EntryCreateRequestTest {

    @Test
    void acceptsCaseInsensitiveKindAndTrimsInput() {
        assertAll(
                () -> assertEquals(EntryKind.DIRECTORY,
                        new EntryCreateRequest(null, "资料", " directory ", null).kindValue()),
                () -> assertEquals(EntryKind.DOCUMENT,
                        new EntryCreateRequest(null, "a.txt", "Document", 1L).kindValue()));
    }

    @Test
    void rejectsUnknownKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new EntryCreateRequest(null, "资料", "folder", null).kindValue());
    }

    @Test
    void directoryNeverConsumesQuotaAndMissingSizeDefaultsToZero() {
        assertAll(
                // 目录即使传了 byteSize 也按 0 计：否则客户端可以靠建目录虚报占用。
                () -> assertEquals(0L,
                        new EntryCreateRequest(null, "资料", "DIRECTORY", 999L).effectiveByteSize()),
                () -> assertEquals(0L,
                        new EntryCreateRequest(null, "a.txt", "DOCUMENT", null).effectiveByteSize()),
                () -> assertEquals(1024L,
                        new EntryCreateRequest(null, "a.txt", "DOCUMENT", 1024L).effectiveByteSize()));
    }
}
