package com.mengde.clouddrive.redis;

import com.mengde.clouddrive.error.UploadInProgressException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 上传相关的分布式锁（Day21）。
 *
 * <p>为什么需要它：Day17/18 的幂等只靠数据库唯一键和 (sessionId, chunkIndex) 主键，
 * 那只能保证"最终不会写坏数据"，不能避免两个并发请求各自把文件写了一遍到磁盘上
 * （先落盘、后写元数据，失败的那个要靠补偿删除）。
 * 用锁把"同一用户的上传"和"同一会话的分片操作"串起来，让浪费的落盘不再发生。</p>
 *
 * <p>为什么 tryLock(0)——不等待、立即失败：上传是长耗时操作，
 * 让后来的请求在锁上排队会把 Tomcat 线程和连接一起占住，
 * 压力大时表现为整体雪崩而不是个别请求失败。快速失败让客户端自己决定是否重试。</p>
 *
 * <p>锁的释放放在 HeldLock.close() 里配合 try-with-resources，
 * 并且先判断 isHeldByCurrentThread：锁可能因为看门狗停止或超时已经被释放，
 * 此时再 unlock 会抛 IllegalMonitorStateException，把一个成功的业务变成 500。</p>
 */
@Component
public class UploadCoordination {

    private static final String LOCK_PREFIX = "clouddrive:lock:";

    private final RedissonClient redisson;

    public UploadCoordination(RedissonClient redisson) {
        this.redisson = redisson;
    }

    public HeldLock acquireOwnerUploadLock(long ownerId) {
        return acquire(LOCK_PREFIX + "upload-owner:" + ownerId, "this owner's upload state");
    }

    public HeldLock acquireSessionLock(String sessionId) {
        return acquire(LOCK_PREFIX + "upload-session:" + sessionId, "this upload session");
    }

    private HeldLock acquire(String key, String resource) {
        RLock lock = redisson.getLock(key);
        try {
            if (!lock.tryLock(0, TimeUnit.MILLISECONDS)) {
                throw new UploadInProgressException(resource);
            }
            return new HeldLock(lock);
        } catch (InterruptedException interrupted) {
            // 恢复中断标志：吞掉中断会让上层的关闭/超时逻辑失灵。
            Thread.currentThread().interrupt();
            throw new UploadInProgressException(resource);
        }
    }

    public static final class HeldLock implements AutoCloseable {

        private final RLock lock;

        private HeldLock(RLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
