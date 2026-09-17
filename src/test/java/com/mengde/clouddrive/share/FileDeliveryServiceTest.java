package com.mengde.clouddrive.share;

import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.FileShareMapper;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileDeliveryServiceTest {
    @Test void ownerRangeIsClampedAndPasscodeGuardsSharedRead() throws Exception {
        DriveEntryRepository entries = mock(DriveEntryRepository.class); FileShareMapper shares = mock(FileShareMapper.class); ObjectStorage objects = mock(ObjectStorage.class);
        StoredDocumentRow document = document(); when(entries.findStoredDocumentByOwnerAndId(7L, 31L)).thenReturn(document);
        when(objects.open("object-key", 2, 4)).thenReturn(new ByteArrayInputStream("cdef".getBytes()));
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(); FileDeliveryService service = new FileDeliveryService(entries, shares, objects, encoder);
        FileDelivery range = service.ownerDownload(7L, 31L, "bytes=2-99");
        assertAll(() -> assertEquals(2, range.start()), () -> assertEquals(5, range.end()), () -> assertEquals("cdef", new String(range.input().readAllBytes())));
        FileShareRow share = new FileShareRow(); share.setToken("known"); share.setOwnerId(7); share.setDocumentId(31); share.setPasscodeHash(encoder.encode("2468")); share.setExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(shares.findByToken("known")).thenReturn(Optional.of(share));
        assertThrows(ShareAccessException.class, () -> service.sharedDownload("known", "wrong", null));
    }
    @Test void createShareHashesPasscodeAndExpiredShareIsDenied() throws Exception {
        DriveEntryRepository entries = mock(DriveEntryRepository.class); FileShareMapper shares = mock(FileShareMapper.class); ObjectStorage objects = mock(ObjectStorage.class);
        when(entries.findStoredDocumentByOwnerAndId(7L, 31L)).thenReturn(document()); BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(); FileDeliveryService service = new FileDeliveryService(entries, shares, objects, encoder);
        FileShareRow created = service.create(7L, 31L, "2468", null);
        assertAll(() -> assertNotEquals("2468", created.getPasscodeHash()), () -> assertTrue(encoder.matches("2468", created.getPasscodeHash())));
        FileShareRow expired = new FileShareRow(); expired.setToken("expired"); expired.setOwnerId(7); expired.setDocumentId(31); expired.setPasscodeHash(encoder.encode("2468")); expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(shares.findByToken("expired")).thenReturn(Optional.of(expired));
        assertThrows(ShareAccessException.class, () -> service.sharedDownload("expired", "2468", null));
    }
    private static StoredDocumentRow document() { StoredDocumentRow row = new StoredDocumentRow(); row.setId(31L); row.setOwnerId(7L); row.setDisplayName("demo.txt"); row.setByteSize(6); row.setStorageKey("object-key"); row.setContentMd5("e80b5017098950fc58aad83c8c14978e"); return row; }
}
