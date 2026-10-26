package com.mengde.clouddrive.application;

import com.mengde.clouddrive.auth.UserAccountMapper;
import com.mengde.clouddrive.error.QuotaExceededException;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import org.springframework.stereotype.Component;

/**
 * 存储配额校验（Day16 的规则，此处集中一份）。
 *
 * <p>抽出来的原因很实际：这段"锁账号行 → 读已用量 → 比较"的逻辑原先在
 * 创建条目、普通上传、分片合并三处各抄了一遍，加上分享转存就是第四遍。
 * 四份拷贝意味着以后改配额规则（比如加上回收站不计入）必须同时改四处，
 * 漏一处就出现"某条路径能超额"的漏洞。</p>
 *
 * <p><b>调用方必须已经在事务中</b>：{@code lockQuotaById} 用的是
 * {@code SELECT ... FOR UPDATE}，行锁只在事务结束时释放。
 * 如果在没有事务的地方调用，锁会立刻释放，等于没锁——
 * 这也是为什么这个方法本身不加 {@code @Transactional}：
 * 它必须并入调用方那个"判断 + 写入"的完整事务，自己另开一个事务反而会破坏保护。</p>
 */
@Component
public class QuotaGuard {

    private final UserAccountMapper users;
    private final DriveEntryRepository entries;

    public QuotaGuard(UserAccountMapper users, DriveEntryRepository entries) {
        this.users = users;
        this.entries = entries;
    }

    /**
     * 要求账号还能再放下 requestedBytes 个字节，否则抛 {@link QuotaExceededException}（→ 409）。
     */
    public void require(long ownerId, long requestedBytes) {
        long quota = users.lockQuotaById(ownerId)
                .orElseThrow(() -> new IllegalStateException("authenticated account no longer exists"));
        long used = entries.usedBytesByOwner(ownerId);
        if (requestedBytes > quota - used) {
            throw new QuotaExceededException(quota, requestedBytes, used);
        }
    }
}
