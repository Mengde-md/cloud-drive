package com.mengde.clouddrive.redis;

import com.mengde.clouddrive.error.UploadInProgressException;
import com.mengde.clouddrive.error.UploadRateLimitExceededException;
import com.mengde.clouddrive.web.DriveEntryWebApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = DriveEntryWebApplication.class, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=root",
        "spring.datasource.password=123456"
})
class UploadCoordinationIntegrationTest {
    @Autowired UploadCoordination coordination;
    @Autowired UploadRequestRateLimiter rateLimiter;
    @Autowired RedissonClient redisson;
    private String sessionId;
    private long ownerId;

    @AfterEach
    void clearDay21Keys() {
        if (sessionId != null) redisson.getKeys().delete("clouddrive:day21:lock:upload-session:" + sessionId);
        if (ownerId != 0) redisson.getKeys().delete("clouddrive:day21:rate:owner:" + ownerId + ":direct_upload");
        if (ownerId != 0) redisson.getKeys().delete("clouddrive:day21:rate:owner:" + (ownerId + 1) + ":direct_upload");
    }

    @Test
    void sameSessionIsRejectedWhileAnotherThreadHoldsTheDistributedLock() throws Exception {
        sessionId = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var ignored = coordination.acquireSessionLock(sessionId)) {
            Future<Throwable> competing = executor.submit(() -> {
                try (var second = coordination.acquireSessionLock(sessionId)) {
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertInstanceOf(UploadInProgressException.class, competing.get());
        } finally {
            executor.shutdownNow();
        }
        assertDoesNotThrow(() -> {
            try (var ignored = coordination.acquireSessionLock(sessionId)) {
                // Successful reacquisition proves close() released the current thread's lock.
            }
        });
    }

    @Test
    void limitIsSharedPerOwnerAndRejectsTheNextWriteWithoutWaiting() {
        ownerId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        for (int attempt = 0; attempt < 5; attempt++) {
            rateLimiter.requirePermit(ownerId, UploadRequestRateLimiter.Operation.DIRECT_UPLOAD);
        }
        assertThrows(UploadRateLimitExceededException.class,
                () -> rateLimiter.requirePermit(ownerId, UploadRequestRateLimiter.Operation.DIRECT_UPLOAD));
        assertDoesNotThrow(() -> rateLimiter.requirePermit(ownerId + 1, UploadRequestRateLimiter.Operation.DIRECT_UPLOAD));
    }
}
