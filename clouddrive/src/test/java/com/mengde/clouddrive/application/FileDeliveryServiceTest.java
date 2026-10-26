package com.mengde.clouddrive.application;

import com.mengde.clouddrive.error.ShareAccessException;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.FileShareMapper;
import com.mengde.clouddrive.persistence.FileShareRow;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import com.mengde.clouddrive.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Day19：Range 解析规则与分享访问校验。
 *
 * <p>Range 解析是纯函数，用单元测试逐条钉死边界，比起接口测试更快也更完整。</p>
 */
class FileDeliveryServiceTest {

    private final DriveEntryRepository entries = mock(DriveEntryRepository.class);
    private final FileShareMapper shares = mock(FileShareMapper.class);
    private final ObjectStorage objects = mock(ObjectStorage.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final QuotaGuard quotaGuard = mock(QuotaGuard.class);
    private final PrivateEntryMetadataCache metadataCache = mock(PrivateEntryMetadataCache.class);
    private final FileDeliveryService service =
            new FileDeliveryService(entries, shares, objects, encoder, quotaGuard, metadataCache,
                    org.mockito.Mockito.mock(com.mengde.clouddrive.analysis.FileAnalysisTrigger.class));

    @Test
    void parsesAllSupportedRangeForms() {
        assertAll(
                // 无 Range：整个文件
                () -> assertArrayEquals(new long[]{0, 9}, service.parse(null, 10)),
                () -> assertArrayEquals(new long[]{0, 9}, service.parse("  ", 10)),
                // 闭区间
                () -> assertArrayEquals(new long[]{2, 5}, service.parse("bytes=2-5", 10)),
                // 开放上界
                () -> assertArrayEquals(new long[]{4, 9}, service.parse("bytes=4-", 10)),
                // suffix range：最后 3 个字节
                () -> assertArrayEquals(new long[]{7, 9}, service.parse("bytes=-3", 10)),
                // suffix 超过文件大小时从 0 开始
                () -> assertArrayEquals(new long[]{0, 9}, service.parse("bytes=-99", 10)),
                // 上界超出时夹到末尾，而不是报错
                () -> assertArrayEquals(new long[]{2, 9}, service.parse("bytes=2-99", 10)));
    }

    @Test
    void rejectsMalformedOrUnsatisfiableRanges() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> service.parse("items=0-1", 10)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.parse("bytes=0-1,3-4", 10)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.parse("bytes=10-12", 10)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.parse("bytes=5-2", 10)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.parse("bytes=x-y", 10)));
    }

    @Test
    void ownerDownloadClampsRangeAndReadsOnlyThatSlice() throws Exception {
        when(entries.findStoredDocumentByOwnerAndId(7L, 31L)).thenReturn(document());
        when(objects.open("object-key", 2, 4))
                .thenReturn(new ByteArrayInputStream("cdef".getBytes(StandardCharsets.UTF_8)));

        FileDeliveryService.FileDelivery range = service.ownerDownload(7L, 31L, "bytes=2-99");

        assertAll(
                () -> assertEquals(2, range.start()),
                () -> assertEquals(5, range.end()),
                () -> assertEquals(6, range.totalSize()),
                () -> assertEquals("cdef",
                        new String(range.input().readAllBytes(), StandardCharsets.UTF_8)));
    }

    @Test
    void sharedDownloadRejectsWrongPasscodeAndExpiredShare() {
        FileShareRow valid = share(encoder.encode("2468"), null);
        FileShareRow expired = share(encoder.encode("2468"), LocalDateTime.now().minusMinutes(1));
        when(shares.findByToken("valid")).thenReturn(Optional.of(valid));
        when(shares.findByToken("expired")).thenReturn(Optional.of(expired));
        when(shares.findByToken("missing")).thenReturn(Optional.empty());

        assertAll(
                () -> assertThrows(ShareAccessException.class,
                        () -> service.sharedDownload("valid", "wrong", null)),
                () -> assertThrows(ShareAccessException.class,
                        () -> service.sharedDownload("valid", null, null)),
                () -> assertThrows(ShareAccessException.class,
                        () -> service.sharedDownload("expired", "2468", null)),
                () -> assertThrows(ShareAccessException.class,
                        () -> service.sharedDownload("missing", "2468", null)));
    }

    @Test
    void shareInfoReturnsFileNameAndSizeAfterPasscodeCheck() {
        when(shares.findByToken("valid")).thenReturn(Optional.of(share(encoder.encode("2468"), null)));
        when(entries.findStoredDocumentByOwnerAndId(7L, 31L)).thenReturn(document());

        FileDeliveryService.ShareInfo info = service.shareInfo("valid", "2468");

        assertAll(
                () -> assertEquals("range.txt", info.displayName()),
                () -> assertEquals(6L, info.byteSize()));
    }

    @Test
    void saveToMyDriveReusesTheSameStorageKeyInsteadOfCopyingBytes() {
        when(shares.findByToken("valid")).thenReturn(Optional.of(share(encoder.encode("2468"), null)));
        when(entries.findStoredDocumentByOwnerAndId(7L, 31L)).thenReturn(document());
        when(entries.saveStoredDocument(anyLong(), any(), anyString(), anyLong(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    StoredDocumentRow saved = new StoredDocumentRow();
                    saved.setId(99L);
                    saved.setOwnerId(invocation.getArgument(0));
                    saved.setDisplayName(invocation.getArgument(2));
                    saved.setByteSize(invocation.getArgument(3));
                    saved.setStorageKey(invocation.getArgument(4));
                    return saved;
                });

        AuthenticatedUser receiver = new AuthenticatedUser(42L, "bob", List.of("USER"));
        StoredDocumentRow saved = service.saveToMyDrive(receiver, "valid", "2468", null, null);

        assertAll(
                // 接收者名下多了一条元数据，但对象键仍是原来那一个——没有复制字节
                () -> assertEquals(42L, saved.getOwnerId()),
                () -> assertEquals("object-key", saved.getStorageKey()),
                () -> assertEquals("range.txt", saved.getDisplayName()));
        // 配额必须按真实大小计入接收者，否则可以靠互相转存无限扩容
        verify(quotaGuard).require(42L, 6L);
        verify(metadataCache).evictOwner(42L);
    }

    @Test
    void saveToMyDriveRejectsWrongPasscodeBeforeTouchingQuota() {
        when(shares.findByToken("valid")).thenReturn(Optional.of(share(encoder.encode("2468"), null)));
        AuthenticatedUser receiver = new AuthenticatedUser(42L, "bob", List.of("USER"));

        assertThrows(ShareAccessException.class,
                () -> service.saveToMyDrive(receiver, "valid", "wrong", null, null));
        verify(quotaGuard, never()).require(anyLong(), anyLong());
    }

    private static StoredDocumentRow document() {
        StoredDocumentRow row = new StoredDocumentRow();
        row.setId(31L);
        row.setOwnerId(7L);
        row.setDisplayName("range.txt");
        row.setByteSize(6L);
        row.setStorageKey("object-key");
        row.setContentMd5("e80b5017098950fc58aad83c8c14978e");
        return row;
    }

    private static FileShareRow share(String passcodeHash, LocalDateTime expiresAt) {
        FileShareRow row = new FileShareRow();
        row.setToken("token");
        row.setDocumentId(31L);
        row.setOwnerId(7L);
        row.setPasscodeHash(passcodeHash);
        row.setExpiresAt(expiresAt);
        return row;
    }
}
