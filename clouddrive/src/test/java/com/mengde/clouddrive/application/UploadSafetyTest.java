package com.mengde.clouddrive.application;

import com.mengde.clouddrive.analysis.FileAnalysisTrigger;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.error.DuplicateEntryNameException;
import com.mengde.clouddrive.error.QuotaExceededException;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.persistence.UploadChunkRow;
import com.mengde.clouddrive.persistence.UploadSessionRepository;
import com.mengde.clouddrive.persistence.UploadSessionRow;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import com.mengde.clouddrive.redis.UploadCoordination;
import com.mengde.clouddrive.storage.ChunkStorage;
import com.mengde.clouddrive.storage.MergedChunks;
import com.mengde.clouddrive.storage.ObjectStorage;
import com.mengde.clouddrive.storage.StoredObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated failure injection: no application boot, external services, database, SMTP or Redis. */
class UploadSafetyTest {
    @TempDir Path temporary;
    private final AuthenticatedUser user = new AuthenticatedUser(7L, "alice", List.of("USER"));
    private final DriveEntryRepository entries = mock(DriveEntryRepository.class);
    private final QuotaGuard quota = mock(QuotaGuard.class);
    private final ObjectStorage objects = mock(ObjectStorage.class);
    private final ChunkStorage chunks = mock(ChunkStorage.class);
    private final UploadSessionRepository sessions = mock(UploadSessionRepository.class);
    private final PrivateEntryMetadataCache cache = mock(PrivateEntryMetadataCache.class);
    private final UploadCoordination coordination = mock(UploadCoordination.class);
    private final FileAnalysisTrigger analysis = mock(FileAnalysisTrigger.class);
    private final DocumentUploadApplicationService direct = new DocumentUploadApplicationService(
            entries, quota, objects, cache, coordination, analysis);
    private final UploadSessionApplicationService multipart = new UploadSessionApplicationService(
            sessions, chunks, objects, entries, quota, cache, coordination, analysis);
    private MergedChunks merged;
    private StoredDocumentRow document;

    @BeforeEach
    void setup() throws IOException {
        when(coordination.acquireOwnerUploadLock(7L)).thenReturn(mock(UploadCoordination.HeldLock.class));
        when(coordination.acquireSessionLock("session")).thenReturn(mock(UploadCoordination.HeldLock.class));
        UploadSessionRow session = new UploadSessionRow();
        session.setId("session");
        session.setOwnerId(7L);
        session.setDisplayName("test.txt");
        session.setTotalChunks(1);
        session.setStatus(UploadSessionRow.STATUS_ACTIVE);
        when(sessions.findActive(7L, "session")).thenReturn(session);
        UploadChunkRow part = new UploadChunkRow();
        part.setChunkIndex(0);
        part.setByteSize(3L);
        when(sessions.chunks("session")).thenReturn(List.of(part));
        Path mergeFile = temporary.resolve("session.merged");
        Files.writeString(mergeFile, "abc");
        merged = new MergedChunks(mergeFile, 3L, "md5");
        when(chunks.merge(eq("session"), anyList())).thenReturn(merged);
        when(objects.store(any(InputStream.class), anyString())).thenReturn(new StoredObject("key", 3L, "md5"));
        document = new StoredDocumentRow();
        document.setId(31L);
        document.setOwnerId(7L);
        document.setStorageKey("key");
        document.setByteSize(3L);
        when(entries.saveStoredDocument(eq(7L), isNull(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(document);
    }

    @AfterEach
    void clearTransactionContext() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "test.txt", "text/plain", "abc".getBytes());
    }

    private void startTransactionCallbacks() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private void finishTransactionCallbacks(int status) {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            callbacks.forEach(TransactionSynchronization::afterCommit);
        }
        callbacks.forEach(callback -> callback.afterCompletion(status));
    }

    @Test
    void overQuotaDirectUploadIsRejectedBeforeObjectStorageOrMetadata() throws IOException {
        QuotaExceededException failure = new QuotaExceededException(2, 3, 0);
        doThrow(failure).when(quota).require(7L, 3L);
        assertSame(failure, assertThrows(QuotaExceededException.class,
                () -> direct.upload(user, null, "test.txt", file())));
        verify(objects, never()).store(any(), any());
        verifyNoInteractions(entries, cache, analysis);
    }

    @Test
    void directUploadChecksReceivedPartThenChecksActualStoredBytes() throws IOException {
        assertSame(document, direct.upload(user, null, "test.txt", file()));
        InOrder order = inOrder(quota, objects, entries);
        order.verify(quota).require(7L, 3L);
        order.verify(objects).store(any(InputStream.class), eq("test.txt"));
        order.verify(quota).require(7L, 3L);
        order.verify(entries).saveStoredDocument(7L, null, "test.txt", 3L, "key", "md5");
    }

    @Test
    void actualSizeCheckStillRejectsUnderreportedPartAndDeletesObject() throws IOException {
        MultipartFile underreported = mock(MultipartFile.class);
        when(underreported.getSize()).thenReturn(2L);
        when(underreported.getOriginalFilename()).thenReturn("test.txt");
        when(underreported.getInputStream()).thenReturn(InputStream.nullInputStream());
        QuotaExceededException failure = new QuotaExceededException(2, 3, 0);
        doThrow(failure).when(quota).require(7L, 3L);
        assertSame(failure, assertThrows(QuotaExceededException.class,
                () -> direct.upload(user, null, "test.txt", underreported)));
        verify(objects).delete("key");
        verifyNoInteractions(entries, cache, analysis);
    }

    @Test
    void compensationIOExceptionNeverReplacesOriginalDuplicateName() throws IOException {
        DuplicateEntryNameException original = new DuplicateEntryNameException("test.txt");
        IOException cleanup = new IOException("injected delete failure");
        when(entries.saveStoredDocument(7L, null, "test.txt", 3L, "key", "md5")).thenThrow(original);
        doThrow(cleanup).when(objects).delete("key");
        assertSame(original, assertThrows(DuplicateEntryNameException.class,
                () -> direct.upload(user, null, "test.txt", file())));
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        verifyNoInteractions(cache, analysis);
    }

    @Test
    void compensationRuntimeFailureAlsoPreservesOriginal() throws IOException {
        DuplicateEntryNameException original = new DuplicateEntryNameException("test.txt");
        IllegalStateException cleanup = new IllegalStateException("injected cleanup runtime failure");
        when(entries.saveStoredDocument(7L, null, "test.txt", 3L, "key", "md5")).thenThrow(original);
        doThrow(cleanup).when(objects).delete("key");
        assertSame(original, assertThrows(DuplicateEntryNameException.class,
                () -> direct.upload(user, null, "test.txt", file())));
        assertSame(cleanup, original.getSuppressed()[0]);
    }

    @Test
    void commitTimeRollbackDeletesNewDirectObjectButCommitDoesNot() throws IOException {
        startTransactionCallbacks();
        direct.upload(user, null, "test.txt", file());
        verify(objects, never()).delete(anyString());
        finishTransactionCallbacks(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(objects).delete("key");
    }

    @Test
    void unknownCommitOutcomeDoesNotRiskDeletingCommittedObject() throws IOException {
        startTransactionCallbacks();
        direct.upload(user, null, "test.txt", file());
        finishTransactionCallbacks(TransactionSynchronization.STATUS_UNKNOWN);
        verify(objects, never()).delete(anyString());
    }

    @Test
    void overQuotaSessionIsRejectedBeforeMergeAndFinalObjectCopy() throws IOException {
        QuotaExceededException failure = new QuotaExceededException(2, 3, 0);
        doThrow(failure).when(quota).require(7L, 3L);
        assertSame(failure, assertThrows(QuotaExceededException.class, () -> multipart.complete(user, "session")));
        verify(chunks, never()).merge(anyString(), anyList());
        verify(objects, never()).store(any(), any());
        verifyNoInteractions(entries, cache, analysis);
    }

    @Test
    void sessionLocksAccountBeforeReadingSessionOrEstablishingUsageSnapshot() throws IOException {
        multipart.complete(user, "session");
        InOrder order = inOrder(quota, sessions);
        order.verify(quota).require(7L, 0L);
        order.verify(sessions).findActive(7L, "session");
        order.verify(sessions).chunks("session");
        order.verify(quota, times(2)).require(7L, 3L);
    }

    @Test
    void mergedActualSizeIsCheckedAgainEvenWhenManifestFits() throws IOException {
        when(objects.store(any(), any())).thenReturn(new StoredObject("key", 4L, "md5"));
        QuotaExceededException failure = new QuotaExceededException(3, 4, 0);
        doThrow(failure).when(quota).require(7L, 4L);
        assertSame(failure, assertThrows(QuotaExceededException.class, () -> multipart.complete(user, "session")));
        verify(objects).delete("key");
        verify(chunks).deleteTemporary(merged);
        verify(sessions, never()).markCompleted(anyString());
    }

    @Test
    void mergedObjectAndTemporaryCleanupFailuresCannotReplaceBusinessFailure() throws IOException {
        DuplicateEntryNameException original = new DuplicateEntryNameException("test.txt");
        IOException objectFailure = new IOException("injected object cleanup failure");
        IOException temporaryFailure = new IOException("injected temp cleanup failure");
        when(entries.saveStoredDocument(7L, null, "test.txt", 3L, "key", "md5")).thenThrow(original);
        doThrow(objectFailure).when(objects).delete("key");
        doThrow(temporaryFailure).when(chunks).deleteTemporary(merged);
        assertSame(original, assertThrows(DuplicateEntryNameException.class, () -> multipart.complete(user, "session")));
        assertArrayEquals(new Throwable[]{objectFailure, temporaryFailure}, original.getSuppressed());
        verify(chunks, never()).clearSession(anyString());
    }

    @Test
    void successfulSessionRemainsSuccessfulIfBestEffortCleanupFails() throws IOException {
        doThrow(new IOException("injected part cleanup failure")).when(chunks).clearSession("session");
        doThrow(new IOException("injected temporary cleanup failure")).when(chunks).deleteTemporary(merged);
        assertSame(document, multipart.complete(user, "session"));
        verify(sessions).markCompleted("session");
        verify(sessions).deleteChunks("session");
        verify(objects, never()).delete(anyString());
    }

    @Test
    void completedSessionPartsAreNotDeletedUntilCommit() throws IOException {
        startTransactionCallbacks();
        assertSame(document, multipart.complete(user, "session"));
        verify(chunks, never()).clearSession(anyString());
        finishTransactionCallbacks(TransactionSynchronization.STATUS_COMMITTED);
        verify(chunks).clearSession("session");
        verify(objects, never()).delete(anyString());
    }

    @Test
    void sessionRollbackKeepsRetryablePartsAndCompensatesFinalObject() throws IOException {
        startTransactionCallbacks();
        multipart.complete(user, "session");
        finishTransactionCallbacks(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(chunks, never()).clearSession(anyString());
        verify(objects).delete("key");
    }

    @Test
    void checkedStorageErrorsAreExplicitlyConfiguredToRollBack() throws NoSuchMethodException {
        var source = new AnnotationTransactionAttributeSource();
        var directMethod = DocumentUploadApplicationService.class.getMethod("upload", AuthenticatedUser.class,
                Long.class, String.class, MultipartFile.class);
        var completeMethod = UploadSessionApplicationService.class.getMethod("complete", AuthenticatedUser.class, String.class);
        assertTrue(source.getTransactionAttribute(directMethod, DocumentUploadApplicationService.class)
                .rollbackOn(new IOException("storage failure")));
        assertTrue(source.getTransactionAttribute(completeMethod, UploadSessionApplicationService.class)
                .rollbackOn(new IOException("storage failure")));
    }
}
