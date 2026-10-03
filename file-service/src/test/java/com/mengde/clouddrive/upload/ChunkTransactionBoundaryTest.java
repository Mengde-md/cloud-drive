package com.mengde.clouddrive.upload;

import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import com.mengde.clouddrive.redis.UploadCoordination;
import com.mengde.clouddrive.remote.QuotaCoordinator;
import com.mengde.clouddrive.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChunkTransactionBoundaryTest {
    private final UploadSessionRepository sessions = mock(UploadSessionRepository.class);
    private final ChunkStorage chunks = mock(ChunkStorage.class);
    private final UploadCoordination locks = mock(UploadCoordination.class);
    private final UploadCoordination.HeldLock held = mock(UploadCoordination.HeldLock.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus status = new SimpleTransactionStatus();
    private final AuthenticatedUser owner = new AuthenticatedUser(9, "owner", List.of("USER"));

    private UploadSessionApplicationService service() {
        var session = new UploadSessionRow();
        session.setStatus("ACTIVE"); session.setTotalChunks(1);
        when(sessions.findOwned("session", 9)).thenReturn(session);
        when(sessions.chunks("session")).thenReturn(List.of());
        when(locks.acquireSessionLock("session")).thenReturn(held);
        when(transactions.getTransaction(any())).thenAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            return status;
        });
        return new UploadSessionApplicationService(sessions, chunks, mock(ObjectStorage.class),
                mock(DriveEntryRepository.class), mock(QuotaCoordinator.class),
                mock(PrivateEntryMetadataCache.class), locks, transactions);
    }

    @Test void manifestCommitHappensBeforeSessionUnlock() throws Exception {
        var service = service();
        when(chunks.store(eq("session"), eq(0), any())).thenReturn(new StoredChunk("session/0.part", 3, "md5"));
        var result = service.putChunk(owner, "session", 0, new MockMultipartFile("file", new byte[]{1, 2, 3}));
        assertFalse(result.alreadyReceived());
        var order = inOrder(sessions, transactions, held);
        order.verify(sessions).saveChunk(any());
        order.verify(transactions).commit(status);
        order.verify(held).close();
    }

    @Test void ioFailureRollsBackBeforeUnlockAndKeepsCheckedContract() throws Exception {
        var service = service();
        when(chunks.store(eq("session"), eq(0), any())).thenThrow(new IOException("disk failure"));
        assertThrows(IOException.class, () -> service.putChunk(owner, "session", 0,
                new MockMultipartFile("file", new byte[]{1})));
        var order = inOrder(transactions, held);
        order.verify(transactions).rollback(status);
        order.verify(held).close();
        verify(sessions, never()).saveChunk(any());
    }
}
