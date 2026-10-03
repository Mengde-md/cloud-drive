package com.mengde.clouddrive.application;

import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import com.mengde.clouddrive.remote.QuotaCoordinator;
import com.mengde.clouddrive.storage.ObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Locale;

@Service
public class DocumentUploadApplicationService {
    private final DriveEntryRepository entries;
    private final ObjectStorage storage;
    private final PrivateEntryMetadataCache cache;
    private final QuotaCoordinator quota;

    public DocumentUploadApplicationService(DriveEntryRepository entries, ObjectStorage storage,
                                            PrivateEntryMetadataCache cache, QuotaCoordinator quota) {
        this.entries = entries;
        this.storage = storage;
        this.cache = cache;
        this.quota = quota;
    }

    public StoredDocumentRow upload(AuthenticatedUser user, Long parentId, String displayName, MultipartFile file) throws IOException {
        verifyParent(user.id(), parentId);
        var stored = storage.store(file.getInputStream(), file.getOriginalFilename());
        var document = quota.execute(user.id(), stored.byteSize(), stored.storageKey(), () -> {
            verifyParent(user.id(), parentId);
            return entries.saveStoredDocument(user.id(), parentId, displayName, stored.byteSize(),
                    stored.storageKey(), stored.md5());
        });
        cache.evictOwner(user.id());
        return document;
    }

    public StoredDocumentRow instantUpload(AuthenticatedUser user, Long parentId, String displayName, String md5) {
        var candidate = entries.findReusableStoredDocument(user.id(), md5.toLowerCase(Locale.ROOT));
        var document = quota.execute(user.id(), candidate.getByteSize(), null, () -> {
            verifyParent(user.id(), parentId);
            var current = entries.findReusableStoredDocument(user.id(), md5.toLowerCase(Locale.ROOT));
            return entries.saveStoredDocument(user.id(), parentId, displayName, current.getByteSize(),
                    current.getStorageKey(), current.getContentMd5());
        });
        cache.evictOwner(user.id());
        return document;
    }

    private void verifyParent(long ownerId, Long parentId) {
        if (parentId != null && !(entries.findByOwnerAndId(ownerId, parentId) instanceof DirectoryEntry)) {
            throw new IllegalArgumentException("parent entry must be a directory");
        }
    }
}
