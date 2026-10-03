package com.mengde.clouddrive.remote;

import com.mengde.clouddrive.persistence.*;
import com.mengde.clouddrive.redis.UploadCoordination;
import com.mengde.clouddrive.storage.ObjectStorage;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QuotaCoordinatorIntegrationTest {
    private static final long OWNER = 900002602L;
    private JdbcTemplate jdbc;
    private QuotaOperationMapper operations;
    private QuotaGateway remote;
    private ObjectStorage storage;
    private QuotaCoordinator coordinator;

    @BeforeEach void setup() throws Exception {
        var data = new DriverManagerDataSource(
                System.getenv().getOrDefault("CLOUDDRIVE_DB_URL", "jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
                System.getenv("CLOUDDRIVE_DB_USERNAME"), System.getenv("CLOUDDRIVE_DB_PASSWORD"));
        jdbc = new JdbcTemplate(data);
        clean();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(data);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(QuotaOperationMapper.class);
        factory.setConfiguration(configuration);
        operations = new SqlSessionTemplate(factory.getObject()).getMapper(QuotaOperationMapper.class);
        remote = mock(QuotaGateway.class);
        storage = mock(ObjectStorage.class);
        var locks = mock(UploadCoordination.class);
        when(locks.acquireOwnerUploadLock(OWNER)).thenReturn(mock(UploadCoordination.HeldLock.class));
        coordinator = new QuotaCoordinator(remote, operations, mock(DriveEntryRepository.class), locks, storage, new JdbcTransactionManager(data));
    }
    @AfterEach void clean() { if (jdbc != null) jdbc.update("DELETE FROM file_quota_operations WHERE owner_id = ?", OWNER); }

    @Test void remoteCommitOnlyRunsAfterLocalTransactionHasCommitted() {
        doAnswer(call -> {
            var reservation = (QuotaGateway.Reservation) call.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            // A separate connection must see the prior local commit, not an uncommitted update.
            try (var connection = jdbc.getDataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT phase FROM file_quota_operations WHERE reservation_id = ?")) {
                statement.setString(1, reservation.id());
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("COMMIT_PENDING");
                }
            }
            return null;
        }).when(remote).commit(any());
        assertThat(coordinator.execute(OWNER, 10, "new-object", () -> "saved")).isEqualTo("saved");
        verify(remote).commit(any());
        verify(remote, never()).release(any());
        assertThat(operations.pending(OWNER)).isEmpty();
    }

    @Test void localRollbackReleasesReservationAndDeletesOnlyNewObject() throws Exception {
        assertThatThrownBy(() -> coordinator.execute(OWNER, 10, "new-object", () -> {
            jdbc.update("UPDATE file_quota_operations SET storage_key = 'should-roll-back' WHERE owner_id = ?", OWNER);
            throw new IllegalArgumentException("metadata failed");
        })).hasMessage("metadata failed");
        verify(remote).release(any());
        verify(remote, never()).commit(any());
        verify(storage).delete("new-object");
        verify(storage, never()).delete("should-roll-back");
        assertThat(operations.pending(OWNER)).isEmpty();
    }

    @Test void lostCommitResponseKeepsCommittedMarkerAndNeverDeletesObject() throws Exception {
        doThrow(new QuotaGateway.QuotaUnavailableException()).when(remote).commit(any());
        assertThat(coordinator.execute(OWNER, 10, "new-object", () -> "saved")).isEqualTo("saved");
        var pending = operations.pending(OWNER);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).phase()).isEqualTo("COMMIT_PENDING");
        verify(remote, never()).release(any());
        verify(storage, never()).delete(anyString());
        doNothing().when(remote).commit(any());
        coordinator.mutate(OWNER, () -> null);
        verify(remote, times(2)).commit(new QuotaGateway.Reservation(OWNER, pending.get(0).id()));
        assertThat(operations.pending(OWNER)).isEmpty();
    }

    @Test void reserveTimeoutAndFailedReleaseRemainRecoverable() {
        doThrow(new QuotaGateway.QuotaUnavailableException()).when(remote).reserve(any(), anyLong(), anyLong());
        doThrow(new QuotaGateway.QuotaUnavailableException()).when(remote).release(any());
        assertThatThrownBy(() -> coordinator.execute(OWNER, 10, null, () -> { throw new AssertionError("must not write"); }))
                .isInstanceOf(QuotaGateway.QuotaUnavailableException.class);
        assertThat(operations.pending(OWNER)).hasSize(1);
        assertThat(operations.pending(OWNER).get(0).phase()).isEqualTo("RELEASE_PENDING");
        doNothing().when(remote).release(any());
        coordinator.mutate(OWNER, () -> null);
        assertThat(operations.pending(OWNER)).isEmpty();
    }

    @Test void previousProcessPreparedOperationIsReleasedBeforeNextWrite() {
        String id = UUID.randomUUID().toString();
        operations.prepare(id, OWNER, null);
        coordinator.mutate(OWNER, () -> {
            verify(remote).release(new QuotaGateway.Reservation(OWNER, id));
            return null;
        });
        assertThat(operations.pending(OWNER)).isEmpty();
    }

    @Test void resumedHolderCannotResurrectCancelledOperation() throws Exception {
        var reserved = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var wrote = new AtomicBoolean();
        var pool = Executors.newSingleThreadExecutor();
        doAnswer(call -> { reserved.countDown(); assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(remote).reserve(any(), anyLong(), anyLong());
        try {
            var old = pool.submit(() -> coordinator.execute(OWNER, 10, "new-object", () -> { wrote.set(true); return "saved"; }));
            assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();
            String id = operations.pending(OWNER).get(0).id();
            coordinator.mutate(OWNER, () -> null);
            verify(storage).delete("new-object");
            resume.countDown();
            assertThatThrownBy(() -> old.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(QuotaGateway.QuotaUnavailableException.class);
            assertThat(wrote).isFalse();
            assertThat(operations.lock(id).phase()).isEqualTo("DONE");
            verify(remote, never()).commit(any());
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    @Test void reconciliationWaitsForMetadataTransactionInsteadOfDeletingItsObject() throws Exception {
        var writing = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var old = pool.submit(() -> coordinator.execute(OWNER, 10, "new-object", () -> {
                writing.countDown();
                try { if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test wait expired"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return "saved";
            }));
            assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
            var next = pool.submit(() -> coordinator.mutate(OWNER, () -> "next"));
            assertThatThrownBy(() -> next.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            verify(storage, never()).delete(anyString());
            finish.countDown();
            assertThat(old.get(10, TimeUnit.SECONDS)).isEqualTo("saved");
            assertThat(next.get(10, TimeUnit.SECONDS)).isEqualTo("next");
            verify(remote, never()).release(any());
            verify(storage, never()).delete(anyString());
            assertThat(operations.pending(OWNER)).isEmpty();
        } finally { finish.countDown(); pool.shutdownNow(); }
    }

    @Test void failedCleanupCannotRollbackCancellationIntentAndReviveOldWriter() throws Exception {
        var reserved = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var wrote = new AtomicBoolean();
        var pool = Executors.newSingleThreadExecutor();
        doAnswer(call -> { reserved.countDown(); assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(remote).reserve(any(), anyLong(), anyLong());
        doThrow(new java.io.IOException("storage unavailable")).when(storage).delete("new-object");
        try {
            var old = pool.submit(() -> coordinator.execute(OWNER, 10, "new-object", () -> { wrote.set(true); return "saved"; }));
            assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> coordinator.mutate(OWNER, () -> null)).isInstanceOf(QuotaGateway.QuotaUnavailableException.class);
            verify(remote).release(any());
            assertThat(operations.pending(OWNER).get(0).phase()).isEqualTo("RELEASE_PENDING");
            resume.countDown();
            assertThatThrownBy(() -> old.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(QuotaGateway.QuotaUnavailableException.class);
            assertThat(wrote).isFalse();
            assertThat(operations.pending(OWNER).get(0).phase()).isEqualTo("RELEASE_PENDING");
            doNothing().when(storage).delete("new-object");
            coordinator.mutate(OWNER, () -> null);
            assertThat(operations.pending(OWNER)).isEmpty();
            verify(remote, never()).commit(any());
        } finally { resume.countDown(); pool.shutdownNow(); }
    }
}
