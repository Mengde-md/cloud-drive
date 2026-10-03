package com.mengde.clouddrive.application;

import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.remote.QuotaCoordinator;
import com.mengde.clouddrive.persistence.DriveEntryMapper;
import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.error.DirectoryNotEmptyException;
import com.mengde.clouddrive.error.QuotaExceededException;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.web.EntryCreateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import com.mengde.clouddrive.web.EntryPageResponse;
import com.mengde.clouddrive.web.EntryResponse;
import com.mengde.clouddrive.web.TrashEntryResponse;
import com.mengde.clouddrive.web.TrashPageResponse;

/** Day16 owner boundary: every entry operation starts from the authenticated principal. */
@Service
public class OwnedDriveEntryApplicationService {
    private final DriveEntryRepository entries;
    private final QuotaCoordinator quota;
    private final DriveEntryMapper mapper;

    public OwnedDriveEntryApplicationService(DriveEntryRepository entries, QuotaCoordinator quota, DriveEntryMapper mapper) {
        this.entries = entries;
        this.quota = quota;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<DriveEntry> list(AuthenticatedUser user) {
        return entries.findAllByOwner(user.id());
    }

    @Transactional(readOnly = true)
    public DriveEntry find(AuthenticatedUser user, long entryId) {
        return entries.findByOwnerAndId(user.id(), entryId);
    }

    @Transactional(readOnly = true)
    public EntryPageResponse page(AuthenticatedUser user, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page must be >= 0 and size must be 1..100");
        List<DriveEntry> all = entries.findAllByOwner(user.id());
        int from = Math.min(page * size, all.size()); int to = Math.min(from + size, all.size());
        return new EntryPageResponse(all.subList(from, to).stream().map(EntryResponse::from).toList(), page, size, all.size());
    }

    @Transactional(readOnly = true)
    public TrashPageResponse trash(AuthenticatedUser user, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page must be >= 0 and size must be 1..100");
        List<TrashEntryResponse> items = entries.findTrashByOwner(user.id(), page, size);
        return new TrashPageResponse(items, page, size, entries.countTrashByOwner(user.id()));
    }

    public DriveEntry create(AuthenticatedUser user, EntryCreateRequest request) {
        long requested = request.effectiveByteSize();
        return quota.execute(user.id(), requested, null, () -> {
            verifyParent(user.id(), request.parentId());
            return entries.saveGenerated(user.id(), request.parentId(), request.displayName(), request.kindValue(), requested);
        });
    }

    @Transactional
    public void rename(AuthenticatedUser user, long entryId, String displayName) {
        entries.renameByOwner(user.id(), entryId, displayName);
    }

    public void delete(AuthenticatedUser user, long entryId) {
        quota.mutate(user.id(), () -> {
        DriveEntry entry = entries.findByOwnerAndId(user.id(), entryId);
        if (entry instanceof DirectoryEntry && entries.hasChildren(user.id(), entryId)) {
            throw new DirectoryNotEmptyException(entryId);
        }
        entries.deleteByOwnerAndId(user.id(), entryId);
        return null;
        });
    }

    public void restore(AuthenticatedUser user, long id) {
        Long amount = mapper.trashedBytes(user.id(), id);
        if (amount == null) throw new com.mengde.clouddrive.error.EntryNotFoundException(id);
        quota.execute(user.id(), amount, null, () -> { entries.restoreByOwnerAndId(user.id(), id); return null; });
    }
    public void purgeTrash(AuthenticatedUser user) {
        quota.mutate(user.id(), () -> { entries.purgeTrashByOwner(user.id()); return null; });
    }

    private void verifyParent(long ownerId, Long parentId) {
        if (parentId == null) {
            return;
        }
        DriveEntry parent = entries.findByOwnerAndId(ownerId, parentId);
        if (!(parent instanceof DirectoryEntry)) {
            throw new IllegalArgumentException("parent entry must be a directory");
        }
    }
}
