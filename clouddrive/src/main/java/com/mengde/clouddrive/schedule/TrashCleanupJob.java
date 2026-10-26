package com.mengde.clouddrive.schedule;

import com.mengde.clouddrive.application.TrashPurgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 回收站自动清理（跨用户的后台任务）。
 *
 * <p>为什么需要它：在这之前系统里一个定时任务都没有，回收站只能靠用户手工点"清空"。
 * 用户不点，被删的文件就永久占着磁盘和配额——对一台 50 GB 系统盘的云服务器来说，
 * 这是个只会单调增长、迟早撑爆的坑。</p>
 *
 * <p>三处刻意的设计：</p>
 * <ol>
 *   <li><b>先查对象键，再删数据库行，事务提交后才删文件</b>。顺序反了（先删文件后删行）
 *       一旦中途失败，数据库里会留下指向已消失文件的元数据，用户点下载会拿到 500；
 *       而按现在的顺序失败，最坏情况只是磁盘上多几个没人引用的垃圾文件，需要另行重试清理。</li>
 *   <li><b>删文件前判断是否仍被引用</b>。秒传让多条元数据共用一个物理文件，
 *       回收站中尚可恢复的条目同样算引用，按条目直接删文件会把别人的内容一起删掉。</li>
 *   <li><b>单个文件删除失败只记日志，不抛异常</b>。元数据已经没了，
 *       残留文件只是占空间的垃圾，不值得让整个清理任务中断，更不该回滚已完成的删除。</li>
 * </ol>
 *
 * <p>已知限制：这是单实例任务，没有做分布式调度。多实例部署时会重复执行——
 * 由于每步操作都是幂等的（删已删除的行影响 0 行、删不存在的文件是 no-op），
 * 重复执行不会出错，但会造成无意义的重复扫描。真要多实例部署，
 * 应当用 Redisson 的锁把这个任务包起来，或交给专门的调度中心。</p>
 */
@Component
public class TrashCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TrashCleanupJob.class);

    private final TrashPurgeService trashPurge;
    private final TrashProperties properties;

    public TrashCleanupJob(TrashPurgeService trashPurge, TrashProperties properties) {
        this.trashPurge = trashPurge;
        this.properties = properties;
    }

    @Scheduled(cron = "${clouddrive.trash.cleanup-cron:0 30 3 * * *}")
    public void cleanupExpiredTrash() {
        int retentionDays = properties.getRetentionDays();
        if (retentionDays <= 0) {
            log.debug("trash auto-cleanup disabled (retentionDays={})", retentionDays);
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int purged = purge(cutoff);
        if (purged > 0) {
            log.info("trash auto-cleanup done retentionDays={} cutoff={} purgedRows={}",
                    retentionDays, cutoff, purged);
        } else {
            log.debug("trash auto-cleanup found nothing older than {}", cutoff);
        }
    }

    /**
     * 独立成方法便于测试；真正的事务在另一个 Spring Bean 上。
     * 即使定时入口在本类内调用本方法，也会经过 TrashPurgeService 的事务代理。
     */
    public int purge(LocalDateTime cutoff) {
        return trashPurge.purgeExpired(cutoff);
    }
}
