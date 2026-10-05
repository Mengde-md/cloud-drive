package com.mengde.clouddrive.persistence;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.domain.EntryKind;
import com.mengde.clouddrive.error.DuplicateEntryException;
import com.mengde.clouddrive.error.EntryNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day07 Repository 隔离测试：验证 {@link DriveEntryRepository} 在数据库读写
 * 的基础上正确做领域对象 ↔ 行互转，并抛出与 Day03 一致的业务异常。
 * 连接本机真实 MySQL（同 MapperTest），聚焦领域语义而非 SQL 原语。
 *
 * 注：早期版本用 H2 模拟 MySQL，违反总纲红线；已改为真实 MySQL。
 */
@SpringBootTest(
        classes = PersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME}",
                "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD}",
                "spring.flyway.enabled=true",
                "mybatis.configuration.map-underscore-to-camel-case=true"
        })
@Transactional
class DriveEntryRepositoryTest {

    @Autowired
    private DriveEntryRepository repository;

    @Test
    void saveDirectoryAndFindById() {
        DriveEntry entry = new DirectoryEntry(101, 10, null, "repo-dir");
        DriveEntry saved = repository.save(entry);

        assertAll(
                () -> assertEquals(101, saved.id()),
                () -> assertEquals(10, saved.ownerId()),
                () -> assertNull(saved.parentId()),
                () -> assertEquals("repo-dir", saved.displayName()),
                () -> assertEquals(EntryKind.DIRECTORY, saved.kind())
        );
    }

    @Test
    void saveDocumentPreservesByteSize() {
        DriveEntry entry = new DocumentEntry(102, 10, null, "repo-doc", 4096);
        DriveEntry saved = repository.save(entry);

        assertTrue(saved instanceof DocumentEntry);
        assertEquals(4096, ((DocumentEntry) saved).byteSize());
    }

    @Test
    void saveDuplicateThrowsDuplicateEntryException() {
        repository.save(new DirectoryEntry(103, 10, null, "first"));

        assertThrows(DuplicateEntryException.class,
                () -> repository.save(new DirectoryEntry(103, 10, null, "second")));
    }

    @Test
    void findByIdMissingThrowsEntryNotFoundException() {
        assertThrows(EntryNotFoundException.class,
                () -> repository.findById(9999));
    }

    @Test
    void findAllReturnsDomainObjects() {
        repository.save(new DirectoryEntry(110, 10, null, "parent"));
        repository.save(new DocumentEntry(111, 10, 110L, "child", 256));

        List<DriveEntry> entries = repository.findAll();
        assertEquals(2, entries.size());
        assertAll(
                () -> assertEquals(EntryKind.DIRECTORY, entries.get(0).kind()),
                () -> assertEquals(110L, entries.get(0).id()),
                () -> assertEquals(EntryKind.DOCUMENT, entries.get(1).kind()),
                () -> assertEquals(256, ((DocumentEntry) entries.get(1)).byteSize())
        );
    }

    @Test
    void renameUpdatesDisplayName() {
        repository.save(new DirectoryEntry(120, 10, null, "before-rename"));
        repository.rename(120, "after-rename");

        DriveEntry found = repository.findById(120);
        assertEquals("after-rename", found.displayName());
    }

    @Test
    void renameMissingThrowsEntryNotFoundException() {
        assertThrows(EntryNotFoundException.class,
                () -> repository.rename(9999, "whatever"));
    }

    @Test
    void deleteRemovesEntry() {
        repository.save(new DirectoryEntry(130, 10, null, "to-delete"));
        repository.deleteById(130);

        assertThrows(EntryNotFoundException.class,
                () -> repository.findById(130));
    }

    @Test
    void saveChildWithParentId() {
        repository.save(new DirectoryEntry(140, 10, null, "parent"));
        repository.save(new DocumentEntry(141, 10, 140L, "child", 100));

        DriveEntry child = repository.findById(141);
        assertEquals(140L, child.parentId());
    }

    @Test
    void countReflectsInserts() {
        assertEquals(0, repository.count());
        repository.save(new DirectoryEntry(150, 10, null, "a"));
        repository.save(new DirectoryEntry(151, 10, null, "b"));
        assertEquals(2, repository.count());
    }
}
