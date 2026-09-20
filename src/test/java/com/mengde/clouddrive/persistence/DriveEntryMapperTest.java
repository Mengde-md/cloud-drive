package com.mengde.clouddrive.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day07 Mapper 隔离测试：连接本机真实 MySQL（127.0.0.1:3306/cloud_drive_learning，
 * 由 Flyway 建表）。不启动 Web 容器。每个测试方法被 {@code @Transactional} 包裹并
 * 在方法结束后回滚，互不污染。
 *
 * 注：早期版本用 H2（MySQL 兼容模式）模拟 MySQL，违反总纲"只靠 H2 代替 MySQL 是禁止"
 * 红线；已改为真实 MySQL（root/123456，本机实测可连）。
 */
@SpringBootTest(
        classes = PersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME:root}",
                "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD:123456}",
                "spring.flyway.enabled=true",
                "mybatis.configuration.map-underscore-to-camel-case=true"
        })
@Transactional
class DriveEntryMapperTest {

    @Autowired
    private DriveEntryMapper mapper;

    @Test
    void insertAndFindById() {
        DriveEntryRow row = new DriveEntryRow(1, 100, null, "mapper-doc", "DOCUMENT", 2048L);
        int affected = mapper.insert(row);

        assertEquals(1, affected);
        Optional<DriveEntryRow> found = mapper.findById(1);
        assertTrue(found.isPresent());
        assertAll(
                () -> assertEquals(1, found.get().id()),
                () -> assertEquals(100, found.get().ownerId()),
                () -> assertNull(found.get().parentId()),
                () -> assertEquals("mapper-doc", found.get().displayName()),
                () -> assertEquals("DOCUMENT", found.get().kind()),
                () -> assertEquals(2048L, found.get().byteSize())
        );
    }

    @Test
    void findByIdReturnsEmptyWhenMissing() {
        Optional<DriveEntryRow> found = mapper.findById(9999);
        assertTrue(found.isEmpty());
    }

    @Test
    void findAllReturnsOrderedRows() {
        mapper.insert(new DriveEntryRow(10, 100, null, "dir-a", "DIRECTORY", null));
        mapper.insert(new DriveEntryRow(5, 100, null, "dir-b", "DIRECTORY", null));
        mapper.insert(new DriveEntryRow(15, 100, 10L, "doc-c", "DOCUMENT", 512L));

        List<DriveEntryRow> rows = mapper.findAll();
        assertEquals(3, rows.size());
        assertEquals(5, rows.get(0).id());
        assertEquals(10, rows.get(1).id());
        assertEquals(15, rows.get(2).id());
    }

    @Test
    void updateDisplayNameAffectsRow() {
        mapper.insert(new DriveEntryRow(20, 200, null, "old-name", "DIRECTORY", null));
        int affected = mapper.updateDisplayName(20, "new-name");

        assertEquals(1, affected);
        DriveEntryRow row = mapper.findById(20).orElseThrow();
        assertEquals("new-name", row.displayName());
    }

    @Test
    void updateDisplayNameOnMissingReturnsZero() {
        int affected = mapper.updateDisplayName(9999, "whatever");
        assertEquals(0, affected);
    }

    @Test
    void deleteByIdRemovesRow() {
        mapper.insert(new DriveEntryRow(30, 300, null, "to-delete", "DOCUMENT", 100L));
        int affected = mapper.deleteById(30);

        assertEquals(1, affected);
        assertTrue(mapper.findById(30).isEmpty());
    }

    @Test
    void countReturnsTotalRows() {
        assertEquals(0, mapper.count());
        mapper.insert(new DriveEntryRow(40, 400, null, "a", "DIRECTORY", null));
        mapper.insert(new DriveEntryRow(41, 400, 40L, "b", "DOCUMENT", 50L));
        assertEquals(2, mapper.count());
    }

    @Test
    void insertDuplicateIdThrows() {
        mapper.insert(new DriveEntryRow(50, 500, null, "first", "DIRECTORY", null));
        // MyBatis-Spring 会把 SQL 重复键翻译成 Spring 的 DuplicateKeyException
        //（不是裸 MyBatis 的 PersistenceException）。
        org.junit.jupiter.api.Assertions.assertThrows(
                DuplicateKeyException.class,
                () -> mapper.insert(new DriveEntryRow(50, 500, null, "second", "DIRECTORY", null))
        );
    }

    @Test
    void insertDirectoryWithNullByteSize() {
        mapper.insert(new DriveEntryRow(60, 600, null, "dir-no-size", "DIRECTORY", null));
        DriveEntryRow row = mapper.findById(60).orElseThrow();
        assertNull(row.byteSize());
    }

    @Test
    void insertDocumentWithZeroByteSize() {
        mapper.insert(new DriveEntryRow(70, 700, null, "empty-doc", "DOCUMENT", 0L));
        DriveEntryRow row = mapper.findById(70).orElseThrow();
        assertNotNull(row.byteSize());
        assertEquals(0L, row.byteSize());
    }
}
