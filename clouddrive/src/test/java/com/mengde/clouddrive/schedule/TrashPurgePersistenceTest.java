package com.mengde.clouddrive.schedule;

import com.mengde.clouddrive.analysis.FileAnalysisTrigger;
import com.mengde.clouddrive.application.DriveEntryApplicationService;
import com.mengde.clouddrive.application.FileDeliveryService;
import com.mengde.clouddrive.application.QuotaGuard;
import com.mengde.clouddrive.application.TrashPurgeService;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.persistence.DriveEntryMapper;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.storage.LocalObjectStorage;
import com.mengde.clouddrive.storage.ObjectStorage;
import com.mengde.clouddrive.storage.StorageProperties;
import com.mengde.clouddrive.storage.StoredObject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real production MyBatis SQL, isolated H2 transactions and disposable disk objects; no MySQL/Redis. */
@SpringJUnitConfig(TrashPurgePersistenceTest.Config.class)
class TrashPurgePersistenceTest {
    private static final byte[] CONTENT = "shared contents remain downloadable".getBytes(StandardCharsets.UTF_8);
    private static final AuthenticatedUser ALICE = new AuthenticatedUser(1, "alice", List.of("USER"));
    private static final AuthenticatedUser BOB = new AuthenticatedUser(2, "bob", List.of("USER"));

    @TempDir Path objectRoot;
    @Autowired JdbcTemplate db;
    @Autowired DriveEntryRepository entries;
    @Autowired DriveEntryApplicationService application;
    @Autowired TrashCleanupJob job;
    @Autowired TestStorage storage;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ControlledCleanupExecutor cleanupExecutor;

    @BeforeEach
    void setup() {
        db.execute("DELETE FROM drive_entries");
        StorageProperties properties = new StorageProperties();
        properties.setRoot(objectRoot.toString());
        storage.delegate = new LocalObjectStorage(properties);
        storage.deleted.clear();
        storage.failures.clear();
        cleanupExecutor.pending.clear();
        cleanupExecutor.reject = false;
    }

    @Test
    void expiringOneOfTwoTrashedReferencesDoesNotDestroyTheRecoverableFile() throws Exception {
        StoredObject object = object();
        LocalDateTime cutoff = LocalDateTime.of(2026, 10, 1, 0, 0);
        addTrash(11, ALICE.id(), "expired.txt", object, cutoff.minusDays(1));
        addTrash(12, ALICE.id(), "recoverable.txt", object, cutoff.plusDays(1));

        assertEquals(1, job.purge(cutoff));
        cleanupExecutor.runPending();

        assertEquals(1, rows());
        assertTrue(Files.exists(objectRoot.resolve(object.storageKey())));
        assertEquals(1, application.restore(ALICE, 12));
        assertDownload(ALICE, 12);
        assertTrue(storage.deleted.isEmpty());
    }

    @Test
    void manualPurgeProtectsAnotherUsersReferenceEvenWhenItIsAlsoInTrash() throws Exception {
        StoredObject object = object();
        addTrash(21, ALICE.id(), "alice.txt", object, LocalDateTime.now());
        addTrash(22, BOB.id(), "bob.txt", object, LocalDateTime.now());

        assertEquals(1, application.purgeTrash(ALICE));
        cleanupExecutor.runPending();

        assertEquals(1, rows());
        assertTrue(entries.isStorageKeyStillReferenced(object.storageKey()));
        assertEquals(1, application.restore(BOB, 22));
        assertDownload(BOB, 22);
        assertTrue(storage.deleted.isEmpty());
    }

    @Test
    void finalReferenceIsDeletedFromDiskOnlyAfterTheOuterMetadataTransactionCommits() throws Exception {
        StoredObject object = object();
        addTrash(31, ALICE.id(), "last.txt", object, LocalDateTime.now());

        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            assertEquals(1, application.purgeTrash(ALICE));
            assertEquals(0, rows());
            assertTrue(Files.exists(objectRoot.resolve(object.storageKey())));
            assertTrue(storage.deleted.isEmpty());
        });

        assertEquals(0, rows());
        assertTrue(Files.exists(objectRoot.resolve(object.storageKey())));
        cleanupExecutor.runPending();
        assertFalse(Files.exists(objectRoot.resolve(object.storageKey())));
        assertEquals(List.of(object.storageKey()), storage.deleted);
    }

    @Test
    void rollingBackMetadataDeletionNeverDeletesThePhysicalObject() throws Exception {
        StoredObject object = object();
        addTrash(41, ALICE.id(), "rollback.txt", object, LocalDateTime.now());

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(ignored -> {
                    application.purgeTrash(ALICE);
                    throw new IllegalStateException("later operation failed");
                }));

        assertEquals(1, rows());
        assertTrue(cleanupExecutor.pending.isEmpty());
        assertTrue(Files.exists(objectRoot.resolve(object.storageKey())));
        assertTrue(storage.deleted.isEmpty());
        assertEquals(1, application.restore(ALICE, 41));
        assertDownload(ALICE, 41);
    }

    @Test
    void aReferenceCommittedDuringPurgeIsSeenByTheFreshPostCommitCheck() throws Exception {
        StoredObject object = object();
        addTrash(45, ALICE.id(), "source.txt", object, LocalDateTime.now());
        TransactionTemplate independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            application.purgeTrash(ALICE);
            independent.executeWithoutResult(other -> db.update("INSERT INTO drive_entries "
                            + "(id,owner_id,display_name,kind,byte_size,storage_key,content_md5) "
                            + "VALUES (46, ?, 'new-copy.txt', 'DOCUMENT', ?, ?, ?)",
                    BOB.id(), object.byteSize(), object.storageKey(), object.md5()));
        });

        cleanupExecutor.runPending();
        assertEquals(1, rows());
        assertTrue(Files.exists(objectRoot.resolve(object.storageKey())));
        assertTrue(storage.deleted.isEmpty());
        assertDownload(BOB, 46);
    }

    @Test
    void actualScheduledEntryUsesTheTransactionalBeanAndRemovesExpiredUnreferencedObjects() throws Exception {
        StoredObject object = object();
        addTrash(51, ALICE.id(), "expired.txt", object, LocalDateTime.now().minusDays(31));

        job.cleanupExpiredTrash();
        cleanupExecutor.runPending();

        assertEquals(0, rows());
        assertFalse(Files.exists(objectRoot.resolve(object.storageKey())));
    }

    @Test
    void oneDiskFailureDoesNotUndoMetadataOrStopOtherObjectsAndDuplicateKeysAreCleanedOnce() throws Exception {
        StoredObject bad = object();
        StoredObject good = object();
        addTrash(61, ALICE.id(), "bad.txt", bad, LocalDateTime.now());
        addTrash(62, ALICE.id(), "good-one.txt", good, LocalDateTime.now());
        addTrash(63, ALICE.id(), "good-two.txt", good, LocalDateTime.now());
        storage.failures.add(bad.storageKey());

        assertEquals(3, application.purgeTrash(ALICE));
        cleanupExecutor.runPending();

        assertEquals(0, rows());
        assertTrue(Files.exists(objectRoot.resolve(bad.storageKey())));
        assertFalse(Files.exists(objectRoot.resolve(good.storageKey())));
        assertEquals(1, storage.deleted.stream().filter(good.storageKey()::equals).count());
    }

    @Test
    void fullOrStoppedCleanupQueueRetainsObjectsWithoutRunningOnTheCommitThread() throws Exception {
        StoredObject object = object();
        addTrash(71, ALICE.id(), "retained.txt", object, LocalDateTime.now());
        cleanupExecutor.reject = true;

        assertEquals(1, application.purgeTrash(ALICE));

        assertEquals(0, rows());
        assertTrue(Files.exists(objectRoot.resolve(object.storageKey())));
        assertTrue(storage.deleted.isEmpty());
        assertTrue(cleanupExecutor.pending.isEmpty());
    }

    private StoredObject object() throws IOException {
        return storage.store(new ByteArrayInputStream(CONTENT), "test.txt");
    }

    private void addTrash(long id, long owner, String name, StoredObject object, LocalDateTime deletedAt) {
        db.update("INSERT INTO drive_entries (id,owner_id,parent_id,display_name,kind,byte_size,storage_key,"
                        + "content_md5,deleted_at,trash_root_id) VALUES (?, ?, NULL, ?, 'DOCUMENT', ?, ?, ?, ?, ?)",
                id, owner, name, object.byteSize(), object.storageKey(), object.md5(), deletedAt, id);
    }

    private int rows() {
        return db.queryForObject("SELECT COUNT(*) FROM drive_entries", Integer.class);
    }

    private void assertDownload(AuthenticatedUser user, long entryId) throws IOException {
        FileDeliveryService delivery = new FileDeliveryService(entries, null, storage, null, null, null, null);
        try (InputStream input = delivery.ownerDownload(user.id(), entryId, null).input()) {
            assertArrayEquals(CONTENT, input.readAllBytes());
        }
    }

    static class TestStorage implements ObjectStorage {
        LocalObjectStorage delegate;
        final List<String> deleted = new ArrayList<>();
        final Set<String> failures = new HashSet<>();
        @Override public StoredObject store(InputStream input, String filename) throws IOException {
            return delegate.store(input, filename);
        }
        @Override public InputStream open(String key, long start, long length) throws IOException {
            return delegate.open(key, start, length);
        }
        @Override public void delete(String key) throws IOException {
            deleted.add(key);
            if (failures.contains(key)) {
                throw new IOException("simulated disk deletion failure");
            }
            delegate.delete(key);
        }
    }

    /** Explicitly drain after commit; deterministic without sleeps or a real worker. */
    static class ControlledCleanupExecutor implements TaskExecutor {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        boolean reject;
        @Override public void execute(Runnable task) {
            if (reject) {
                throw new TaskRejectedException("simulated bounded cleanup queue rejection");
            }
            pending.add(task);
        }
        void runPending() {
            while (!pending.isEmpty()) {
                pending.remove().run();
            }
        }
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = DriveEntryMapper.class, annotationClass = Mapper.class)
    static class Config {
        @Bean DataSource source() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:trash_purge_regression;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            new JdbcTemplate(source).execute("CREATE TABLE drive_entries (id BIGINT PRIMARY KEY, "
                    + "owner_id BIGINT NOT NULL, parent_id BIGINT, display_name VARCHAR(200), kind VARCHAR(20), "
                    + "byte_size BIGINT, storage_key VARCHAR(255), content_md5 VARCHAR(32), deleted_at TIMESTAMP, "
                    + "trash_root_id BIGINT)");
            return source;
        }
        @Bean JdbcTemplate db(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactions(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean DriveEntryRepository entries(DriveEntryMapper mapper) { return new DriveEntryRepository(mapper); }
        @Bean TestStorage storage() { return new TestStorage(); }
        @Bean("trashObjectCleanupExecutor") ControlledCleanupExecutor cleanupExecutor() {
            return new ControlledCleanupExecutor();
        }
        @Bean TrashPurgeService trashPurge(DriveEntryRepository entries, TestStorage storage,
                                          PlatformTransactionManager transactions,
                                          ControlledCleanupExecutor cleanupExecutor) {
            return new TrashPurgeService(entries, storage, transactions, cleanupExecutor);
        }
        @Bean DriveEntryApplicationService application(DriveEntryRepository entries, TrashPurgeService purge) {
            return new DriveEntryApplicationService(entries, mock(QuotaGuard.class), purge,
                    mock(FileAnalysisTrigger.class));
        }
        @Bean TrashCleanupJob job(TrashPurgeService purge) {
            TrashProperties properties = new TrashProperties();
            properties.setRetentionDays(30);
            return new TrashCleanupJob(purge, properties);
        }
    }
}
