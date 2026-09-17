package com.mengde.clouddrive.application;

import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.auth.UserAccountMapper;
import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.error.QuotaExceededException;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.storage.ObjectStorage;
import com.mengde.clouddrive.storage.StoredObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

/** Day17: local object write plus metadata compensation, not chunked upload. */
@Service
public class DocumentUploadApplicationService {
    private final DriveEntryRepository entries; private final UserAccountMapper users; private final ObjectStorage storage;
    public DocumentUploadApplicationService(DriveEntryRepository entries, UserAccountMapper users, ObjectStorage storage) { this.entries = entries; this.users = users; this.storage = storage; }

    @Transactional
    public StoredDocumentRow upload(AuthenticatedUser user, Long parentId, String displayName, MultipartFile file) throws IOException {
        verifyParent(user.id(), parentId);
        StoredObject stored = storage.store(file.getInputStream(), file.getOriginalFilename());
        try {
            verifyQuota(user.id(), stored.byteSize());
            return entries.saveStoredDocument(user.id(), parentId, displayName, stored.byteSize(), stored.storageKey(), stored.md5());
        } catch (RuntimeException failure) {
            storage.delete(stored.storageKey());
            throw failure;
        }
    }

    @Transactional
    public StoredDocumentRow instantUpload(AuthenticatedUser user, Long parentId, String displayName, String md5) {
        verifyParent(user.id(), parentId);
        StoredDocumentRow candidate = entries.findReusableStoredDocument(user.id(), md5.toLowerCase(Locale.ROOT));
        verifyQuota(user.id(), candidate.getByteSize());
        return entries.saveStoredDocument(user.id(), parentId, displayName, candidate.getByteSize(), candidate.getStorageKey(), candidate.getContentMd5());
    }

    private void verifyQuota(long ownerId, long requested) {
        long quota = users.lockQuotaById(ownerId).orElseThrow(() -> new IllegalArgumentException("authenticated account no longer exists"));
        long used = entries.usedBytesByOwner(ownerId);
        if (requested > quota - used) throw new QuotaExceededException(quota, requested, used);
    }
    private void verifyParent(long ownerId, Long parentId) {
        if (parentId == null) return;
        DriveEntry parent = entries.findByOwnerAndId(ownerId, parentId);
        if (!(parent instanceof DirectoryEntry)) throw new IllegalArgumentException("parent entry must be a directory");
    }
}
