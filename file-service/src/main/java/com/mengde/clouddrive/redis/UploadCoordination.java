package com.mengde.clouddrive.redis;

import com.mengde.clouddrive.error.UploadInProgressException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Day21: Redis-backed mutual exclusion for work that may arrive at different application nodes. */
@Component
public class UploadCoordination {
    private final RedissonClient redisson;

    public UploadCoordination(RedissonClient redisson) { this.redisson = redisson; }

    public HeldLock acquireOwnerUploadLock(long ownerId) {
        return acquire("clouddrive:day21:lock:upload-owner:" + ownerId, "this owner's upload state");
    }

    public HeldLock acquireSessionLock(String sessionId) {
        return acquire("clouddrive:day21:lock:upload-session:" + sessionId, "this upload session");
    }

    private HeldLock acquire(String key, String resource) {
        RLock lock = redisson.getLock(key);
        try {
            // No lease time: Redisson's watchdog renews a live holder and its timeout releases a crashed holder.
            if (!lock.tryLock(0, TimeUnit.MILLISECONDS)) throw new UploadInProgressException(resource);
            return new HeldLock(lock);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UploadInProgressException(resource);
        }
    }

    public static final class HeldLock implements AutoCloseable {
        private final RLock lock;
        private HeldLock(RLock lock) { this.lock = lock; }

        @Override
        public void close() {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
