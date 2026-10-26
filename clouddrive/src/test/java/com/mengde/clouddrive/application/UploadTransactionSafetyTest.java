package com.mengde.clouddrive.application;

import com.mengde.clouddrive.analysis.FileAnalysisTrigger;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.error.UploadSessionIncompleteException;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.persistence.UploadChunkRow;
import com.mengde.clouddrive.persistence.UploadSessionRepository;
import com.mengde.clouddrive.persistence.UploadSessionRow;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import com.mengde.clouddrive.redis.UploadCoordination;
import com.mengde.clouddrive.storage.LocalChunkStorage;
import com.mengde.clouddrive.storage.LocalObjectStorage;
import com.mengde.clouddrive.storage.StorageProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real Spring proxy/transaction manager, isolated H2 transactions and temporary filesystem objects.
 * Repository boundaries are mocked with JdbcTemplate answers: this tests transaction completion
 * and filesystem compensation, NOT production MyBatis SQL, quota locking or Redis coordination.
 */
class UploadTransactionSafetyTest {
    @TempDir Path temporary;
    private static final AuthenticatedUser USER = new AuthenticatedUser(7, "alice", List.of("USER"));
    private AnnotationConfigApplicationContext context;
    private Fixture fixture;
    private DocumentUploadApplicationService direct;
    private UploadSessionApplicationService multipart;

    @BeforeEach
    void setup() throws IOException {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:upload_transaction_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        fixture = new Fixture(temporary, source);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(Fixture.class, () -> fixture);
        context.register(Config.class);
        context.refresh();
        direct = context.getBean(DocumentUploadApplicationService.class);
        multipart = context.getBean(UploadSessionApplicationService.class);
        assertTrue(AopUtils.isAopProxy(direct));
        assertTrue(AopUtils.isAopProxy(multipart));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @AfterEach
    void closeIsolatedContext() {
        try {
            if (context != null) context.close();
        } finally {
            // This is the uniquely named in-memory test database, never the application datasource.
            if (fixture != null) fixture.db.execute("SHUTDOWN");
        }
    }

    @Test
    void directUploadCommitFailureRollsBackMetadataAndCompensatesItsRealObject() {
        fixture.failBeforeCommit = true;

        assertSame(fixture.commitFailure, assertThrows(IllegalStateException.class,
                () -> direct.upload(USER, null, "direct.txt", file())));

        assertEquals(1, fixture.beforeCommitChecks);
        assertEquals(0, fixture.documentCount());
        assertNotNull(fixture.lastStorageKey);
        assertFalse(Files.exists(fixture.objectPath()));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        verify(fixture.analysis).uploaded(any(StoredDocumentRow.class));
    }

    @Test
    void sessionCommitFailureRestoresManifestAndPartsAndTheSameSessionCanBeRetried() throws IOException {
        fixture.seedSession();
        fixture.failBeforeCommit = true;

        assertSame(fixture.commitFailure, assertThrows(IllegalStateException.class,
                () -> multipart.complete(USER, "session")));

        assertEquals(1, fixture.beforeCommitChecks);
        assertEquals(0, fixture.documentCount());
        assertFalse(Files.exists(fixture.objectPath()));
        fixture.assertRetryableSession();
        assertEquals(0, fixture.clearCalls);
        assertFalse(Files.exists(fixture.chunkRoot.resolve("session.merged")));

        fixture.failBeforeCommit = false;
        StoredDocumentRow retried = multipart.complete(USER, "session");
        assertEquals(2, fixture.beforeCommitChecks);
        fixture.assertCommittedSession(retried);
    }

    @Test
    void normalSessionCommitKeepsTheObjectAndOnlyThenClearsOriginalParts() throws IOException {
        fixture.seedSession();

        StoredDocumentRow document = multipart.complete(USER, "session");

        assertEquals(1, fixture.beforeCommitChecks);
        fixture.assertCommittedSession(document);
    }

    @Test
    void normalDirectCommitKeepsBothMetadataAndPhysicalContent() throws IOException {
        StoredDocumentRow document = direct.upload(USER, null, "direct.txt", file());

        assertEquals(1, fixture.beforeCommitChecks);
        assertEquals(1, fixture.documentCount());
        assertEquals(document.getStorageKey(), fixture.lastStorageKey);
        assertEquals("abcdef", Files.readString(fixture.objectPath()));
        assertEquals(0, fixture.clearCalls);
    }

    @Test
    void checkedStorageFailureRollsBackSessionTransactionAndKeepsItsOriginalParts() throws IOException {
        fixture.seedSession();
        IOException failure = fixture.injectCheckedStorageFailure();

        assertSame(failure, assertThrows(IOException.class, () -> multipart.complete(USER, "session")));

        assertEquals(0, fixture.db.queryForObject("SELECT COUNT(*) FROM test_probe", Integer.class));
        assertEquals(0, fixture.documentCount());
        assertEquals(0, fixture.beforeCommitChecks);
        fixture.assertRetryableSession();
        assertEquals(0, fixture.clearCalls);
        assertFalse(Files.exists(fixture.chunkRoot.resolve("session.merged")));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void checkedStorageFailureAlsoRollsBackTheDirectUploadTransaction() throws IOException {
        IOException failure = fixture.injectCheckedStorageFailure();

        assertSame(failure, assertThrows(IOException.class,
                () -> direct.upload(USER, null, "direct.txt", file())));

        assertEquals(0, fixture.db.queryForObject("SELECT COUNT(*) FROM test_probe", Integer.class));
        assertEquals(0, fixture.documentCount());
        assertEquals(0, fixture.beforeCommitChecks);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "direct.txt", "text/plain", "abcdef".getBytes(StandardCharsets.UTF_8));
    }

    static class Fixture {
        final DataSource source;
        final JdbcTemplate db;
        final Path objectRoot;
        final Path chunkRoot;
        final LocalObjectStorage objects;
        final LocalChunkStorage chunks;
        final DriveEntryRepository entries = mock(DriveEntryRepository.class);
        final UploadSessionRepository sessions = mock(UploadSessionRepository.class);
        final QuotaGuard quota = mock(QuotaGuard.class);
        final PrivateEntryMetadataCache cache = mock(PrivateEntryMetadataCache.class);
        final UploadCoordination coordination = mock(UploadCoordination.class);
        final FileAnalysisTrigger analysis = mock(FileAnalysisTrigger.class);
        final IllegalStateException commitFailure = new IllegalStateException("injected beforeCommit failure");
        boolean failBeforeCommit;
        boolean sessionExpected;
        int beforeCommitChecks;
        int clearCalls;
        String lastStorageKey;

        Fixture(Path root, DataSource source) throws IOException {
            this.source = source;
            this.db = new JdbcTemplate(source);
            this.objectRoot = root.resolve("objects");
            this.chunkRoot = root.resolve("chunks");
            StorageProperties properties = new StorageProperties();
            properties.setRoot(objectRoot.toString());
            properties.setChunkRoot(chunkRoot.toString());
            objects = spy(new LocalObjectStorage(properties));
            chunks = spy(new LocalChunkStorage(properties));
            db.execute("CREATE TABLE test_documents (id BIGINT PRIMARY KEY, owner_id BIGINT, display_name VARCHAR(255), "
                    + "byte_size BIGINT, storage_key VARCHAR(255), content_md5 VARCHAR(64))");
            db.execute("CREATE TABLE test_sessions (id VARCHAR(64) PRIMARY KEY, status VARCHAR(20) NOT NULL)");
            db.execute("CREATE TABLE test_chunks (session_id VARCHAR(64), chunk_index INT, byte_size BIGINT, "
                    + "storage_key VARCHAR(255), content_md5 VARCHAR(64), PRIMARY KEY(session_id, chunk_index))");
            db.execute("CREATE TABLE test_probe (id INT PRIMARY KEY)");
            when(coordination.acquireOwnerUploadLock(7L)).thenReturn(mock(UploadCoordination.HeldLock.class));
            when(coordination.acquireSessionLock("session")).thenReturn(mock(UploadCoordination.HeldLock.class));
            when(entries.saveStoredDocument(eq(7L), isNull(), anyString(), anyLong(), anyString(), anyString()))
                    .thenAnswer(call -> {
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        StoredDocumentRow row = new StoredDocumentRow();
                        row.setId(1L);
                        row.setOwnerId(call.getArgument(0));
                        row.setDisplayName(call.getArgument(2));
                        row.setByteSize(call.getArgument(3));
                        row.setStorageKey(call.getArgument(4));
                        row.setContentMd5(call.getArgument(5));
                        lastStorageKey = row.getStorageKey();
                        db.update("INSERT INTO test_documents VALUES (?, ?, ?, ?, ?, ?)", row.getId(), row.getOwnerId(),
                                row.getDisplayName(), row.getByteSize(), row.getStorageKey(), row.getContentMd5());
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                beforeCommitChecks++;
                                // The service has returned through its final analysis notification before
                                // Spring attempts commit; all metadata writes are still uncommitted here.
                                verify(analysis, atLeastOnce()).uploaded(any(StoredDocumentRow.class));
                                assertEquals(1, documentCount());
                                assertTrue(Files.exists(objectPath()));
                                if (sessionExpected) {
                                    assertEquals("COMPLETED", sessionStatus());
                                    assertEquals(0, chunkCount());
                                    assertPartsExist();
                                    assertEquals(0, clearCalls);
                                    assertFalse(Files.exists(chunkRoot.resolve("session.merged")));
                                }
                                if (failBeforeCommit) throw commitFailure;
                            }
                        });
                        return row;
                    });
            when(sessions.findActive(7L, "session")).thenAnswer(call -> {
                if (!"ACTIVE".equals(sessionStatus())) throw new UploadSessionIncompleteException("already completed");
                UploadSessionRow row = new UploadSessionRow();
                row.setId("session");
                row.setOwnerId(7L);
                row.setDisplayName("merged.txt");
                row.setTotalChunks(2);
                row.setStatus("ACTIVE");
                return row;
            });
            when(sessions.chunks("session")).thenAnswer(call -> db.query(
                    "SELECT * FROM test_chunks WHERE session_id = 'session' ORDER BY chunk_index", (rs, index) -> {
                        UploadChunkRow row = new UploadChunkRow();
                        row.setSessionId(rs.getString("session_id"));
                        row.setChunkIndex(rs.getInt("chunk_index"));
                        row.setByteSize(rs.getLong("byte_size"));
                        row.setStorageKey(rs.getString("storage_key"));
                        row.setContentMd5(rs.getString("content_md5"));
                        return row;
                    }));
            doAnswer(call -> { db.update("UPDATE test_sessions SET status = 'COMPLETED' WHERE id = 'session'"); return null; })
                    .when(sessions).markCompleted("session");
            doAnswer(call -> { db.update("DELETE FROM test_chunks WHERE session_id = 'session'"); return null; })
                    .when(sessions).deleteChunks("session");
            doAnswer(call -> {
                // A separate physical connection can see the document only AFTER actual commit.
                try (var connection = source.getConnection(); var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COUNT(*) FROM test_documents")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                assertTrue(beforeCommitChecks > 0);
                clearCalls++;
                return call.callRealMethod();
            }).when(chunks).clearSession("session");
        }

        void seedSession() throws IOException {
            sessionExpected = true;
            db.update("INSERT INTO test_sessions VALUES ('session', 'ACTIVE')");
            for (int index = 0; index < 2; index++) {
                byte[] content = (index == 0 ? "abc" : "def").getBytes(StandardCharsets.UTF_8);
                var part = chunks.store("session", index, new ByteArrayInputStream(content));
                db.update("INSERT INTO test_chunks VALUES (?, ?, ?, ?, ?)", "session", index,
                        part.byteSize(), part.storageKey(), part.md5());
            }
        }

        IOException injectCheckedStorageFailure() throws IOException {
            IOException failure = new IOException("injected storage IOException");
            doAnswer(call -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                db.update("INSERT INTO test_probe VALUES (1)");
                ((InputStream) call.getArgument(0)).close();
                throw failure;
            }).when(objects).store(any(InputStream.class), anyString());
            return failure;
        }

        Path objectPath() { return objectRoot.resolve(lastStorageKey); }
        int documentCount() { return db.queryForObject("SELECT COUNT(*) FROM test_documents", Integer.class); }
        int chunkCount() { return db.queryForObject("SELECT COUNT(*) FROM test_chunks", Integer.class); }
        String sessionStatus() { return db.queryForObject("SELECT status FROM test_sessions WHERE id = 'session'", String.class); }

        void assertPartsExist() {
            assertTrue(Files.exists(chunkRoot.resolve("session/0.part")));
            assertTrue(Files.exists(chunkRoot.resolve("session/1.part")));
        }

        void assertRetryableSession() throws IOException {
            assertEquals("ACTIVE", sessionStatus());
            assertEquals(2, chunkCount());
            assertPartsExist();
            assertEquals("abc", Files.readString(chunkRoot.resolve("session/0.part")));
            assertEquals("def", Files.readString(chunkRoot.resolve("session/1.part")));
        }

        void assertCommittedSession(StoredDocumentRow document) throws IOException {
            assertEquals("COMPLETED", sessionStatus());
            assertEquals(0, chunkCount());
            assertEquals(1, documentCount());
            assertEquals(1, clearCalls);
            assertFalse(Files.exists(chunkRoot.resolve("session")));
            assertFalse(Files.exists(chunkRoot.resolve("session.merged")));
            assertEquals(document.getStorageKey(), lastStorageKey);
            assertEquals("abcdef", Files.readString(objectPath()));
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean PlatformTransactionManager transactions(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean DocumentUploadApplicationService direct(Fixture fixture) {
            return new DocumentUploadApplicationService(fixture.entries, fixture.quota, fixture.objects,
                    fixture.cache, fixture.coordination, fixture.analysis);
        }
        @Bean UploadSessionApplicationService multipart(Fixture fixture) {
            return new UploadSessionApplicationService(fixture.sessions, fixture.chunks, fixture.objects,
                    fixture.entries, fixture.quota, fixture.cache, fixture.coordination, fixture.analysis);
        }
    }
}
