package com.mengde.clouddrive.persistence;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.domain.EntryKind;
import com.mengde.clouddrive.error.DuplicateEntryException;
import com.mengde.clouddrive.error.EntryNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 持久化门面：把领域对象 {@link DriveEntry} 与数据库行
 * {@link DriveEntryRow} 互转，并在保存/查询时抛出与
 * Day03 一致的业务异常（重复键 → 409，缺失 → 404），
 * 使上层 Controller 无需感知 MyBatis 或 SQL。
 */
@Repository
public class DriveEntryRepository {

    private final DriveEntryMapper mapper;

    public DriveEntryRepository(DriveEntryMapper mapper) {
        this.mapper = mapper;
    }

    public DriveEntry save(DriveEntry entry) {
        try {
            mapper.insert(toRow(entry));
        } catch (DuplicateKeyException exception) {
            throw new DuplicateEntryException(entry.id());
        }
        return findById(entry.id());
    }

    public DriveEntry findById(long id) {
        return mapper.findById(id)
                .map(this::toDomain)
                .orElseThrow(() -> new EntryNotFoundException(id));
    }

    public List<DriveEntry> findAll() {
        return mapper.findAll().stream()
                .map(this::toDomain)
                .toList();
    }

    public void rename(long id, String displayName) {
        int updated = mapper.updateDisplayName(id, displayName);
        if (updated == 0) {
            throw new EntryNotFoundException(id);
        }
    }

    public void deleteById(long id) {
        mapper.deleteById(id);
    }

    public void deleteAll() {
        mapper.deleteAll();
    }

    public int count() {
        return mapper.count();
    }

    private DriveEntryRow toRow(DriveEntry entry) {
        Long byteSize = entry instanceof DocumentEntry doc ? doc.byteSize() : null;
        return new DriveEntryRow(
                entry.id(),
                entry.ownerId(),
                entry.parentId(),
                entry.displayName(),
                entry.kind().name(),
                byteSize);
    }

    private DriveEntry toDomain(DriveEntryRow row) {
        if (EntryKind.valueOf(row.kind()) == EntryKind.DIRECTORY) {
            return new DirectoryEntry(row.id(), row.ownerId(), row.parentId(), row.displayName());
        }
        long byteSize = row.byteSize() != null ? row.byteSize() : 0L;
        return new DocumentEntry(row.id(), row.ownerId(), row.parentId(), row.displayName(), byteSize);
    }
}
