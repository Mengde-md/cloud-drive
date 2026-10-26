package com.mengde.clouddrive.application;

import com.mengde.clouddrive.analysis.FileAnalysisTrigger;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.error.ShareAccessException;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.FileShareMapper;
import com.mengde.clouddrive.persistence.FileShareRow;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import com.mengde.clouddrive.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 下载（含 HTTP Range）、提取码分享与分享转存（Day19，转存为后续补充）。
 *
 * <p>Range 解析是这里最容易写错的地方，逐条说明规则：</p>
 * <ul>
 *   <li>没有 Range 头 → 整个文件，返回 200；</li>
 *   <li>{@code bytes=2-5} → 闭区间，包含第 5 字节；</li>
 *   <li>{@code bytes=2-} → 从 2 到文件末尾；</li>
 *   <li>{@code bytes=-500} → 最后 500 字节（suffix range），不是"到第 500 字节"；</li>
 *   <li>end 超出文件大小 → 夹到 size-1，而不是报错（RFC 7233 要求如此，
 *       播放器经常请求一个比文件更大的上界）；</li>
 *   <li>start >= size 或 end < start → 非法，拒绝；</li>
 *   <li>多区间（含逗号）→ 本实现不支持，明确拒绝而不是只取第一段。</li>
 * </ul>
 */
@Service
public class FileDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(FileDeliveryService.class);

    private final DriveEntryRepository entries;
    private final FileShareMapper shares;
    private final ObjectStorage objects;
    private final PasswordEncoder passwords;
    private final QuotaGuard quotaGuard;
    private final PrivateEntryMetadataCache metadataCache;
    private final FileAnalysisTrigger analysis;

    public FileDeliveryService(DriveEntryRepository entries,
                              FileShareMapper shares,
                              ObjectStorage objects,
                              PasswordEncoder passwords,
                              QuotaGuard quotaGuard,
                              PrivateEntryMetadataCache metadataCache,
                              FileAnalysisTrigger analysis) {
        this.entries = entries;
        this.shares = shares;
        this.objects = objects;
        this.passwords = passwords;
        this.quotaGuard = quotaGuard;
        this.metadataCache = metadataCache;
        this.analysis = analysis;
    }

    /* ---------------- 分享管理（所有者视角） ---------------- */

    public FileShareRow create(long ownerId, long documentId, String passcode, LocalDateTime expiresAt) {
        // 先确认这个文件确实属于当事人，避免"给别人的文件生成分享链接"。
        StoredDocumentRow document = entries.findStoredDocumentByOwnerAndId(ownerId, documentId);
        FileShareRow row = new FileShareRow();
        row.setToken(UUID.randomUUID().toString());
        row.setDocumentId(document.getId());
        row.setOwnerId(ownerId);
        // 提取码同样只存摘要：分享表被读到也无法反推提取码。
        row.setPasscodeHash(passwords.encode(passcode));
        row.setExpiresAt(expiresAt);
        shares.insert(row);
        log.info("share created ownerId={} documentId={} token={}", ownerId, documentId, row.getToken());
        return row;
    }

    public List<FileShareRow> listShares(long ownerId) {
        return shares.findByOwner(ownerId);
    }

    public void revokeShare(long ownerId, String token) {
        if (shares.deleteByOwnerAndToken(ownerId, token) == 0) {
            throw new ShareAccessException();
        }
        log.info("share revoked ownerId={} token={}", ownerId, token);
    }

    /**
     * 重置提取码（只有分享的创建者能做）。
     *
     * <p>提取码只存摘要，忘了就无法回显。没有这个能力时用户只能取消分享重建，
     * 而重建会换一个新 token，已经发出去的链接全部失效。
     * 重置只改口令、保留 token，旧链接继续可用、旧口令立即失效。</p>
     */
    public void resetPasscode(long ownerId, String token, String newPasscode) {
        if (shares.updatePasscode(ownerId, token, passwords.encode(newPasscode)) == 0) {
            throw new ShareAccessException();
        }
        log.info("share passcode reset ownerId={} token={}", ownerId, token);
    }

    /* ---------------- 分享访问（接收者视角） ---------------- */

    /**
     * 查看分享的文件信息。
     *
     * <p>接收者在下载之前总得知道这是什么文件、多大。
     * 没有这个接口的话，界面只能让人"盲下"——点了才知道下到的是什么。</p>
     */
    public ShareInfo shareInfo(String token, String passcode) {
        FileShareRow share = requireValidShare(token, passcode);
        StoredDocumentRow document =
                entries.findStoredDocumentByOwnerAndId(share.getOwnerId(), share.getDocumentId());
        return new ShareInfo(document.getDisplayName(), document.getByteSize(), share.getExpiresAt());
    }

    public FileDelivery ownerDownload(long ownerId, long documentId, String range) throws IOException {
        return delivery(entries.findStoredDocumentByOwnerAndId(ownerId, documentId), range);
    }

    /**
     * 凭 token + 提取码的匿名下载。
     */
    public FileDelivery sharedDownload(String token, String passcode, String range) throws IOException {
        FileShareRow share = requireValidShare(token, passcode);
        return delivery(
                entries.findStoredDocumentByOwnerAndId(share.getOwnerId(), share.getDocumentId()),
                range);
    }

    /**
     * 把别人分享的文件转存到自己的网盘。
     *
     * <p>实现上复用了秒传的思路：<b>不复制字节</b>，只在接收者名下新建一条元数据，
     * 指向同一个 storage_key。所以转存一个 1 GB 的文件是毫秒级的，磁盘也不会多占一份。</p>
     *
     * <p>这样做安全吗——会不会让接收者拿到不该拿的东西？不会：
     * 能走到这里的前提是他已经通过了 token + 提取码校验，
     * 也就是说他本来就有权下载这个文件的全部内容，转存只是把"下载权"变成"持有"。</p>
     *
     * <p>但它引出一个必须处理的后果：同一个物理文件现在被<b>不同用户</b>引用。
     * 因此清理磁盘对象时的引用计数（{@code countStorageReferences}）包含回收站且不带 owner 条件，
     * 是全库统计——否则 A 清空回收站会把 B 转存的那份文件内容一起删掉。</p>
     *
     * <p>配额按文件真实大小计入接收者账号：虽然磁盘没有多占，
     * 但如果不计配额，用户就能靠互相转存无限扩容。</p>
     */
    @Transactional
    public StoredDocumentRow saveToMyDrive(AuthenticatedUser user, String token, String passcode,
                                           Long parentId, String displayName) {
        FileShareRow share = requireValidShare(token, passcode);
        StoredDocumentRow source =
                entries.findStoredDocumentByOwnerAndId(share.getOwnerId(), share.getDocumentId());

        String targetName = (displayName == null || displayName.isBlank())
                ? source.getDisplayName()
                : displayName.trim();

        quotaGuard.require(user.id(), source.getByteSize());
        StoredDocumentRow saved = entries.saveStoredDocument(
                user.id(), parentId, targetName,
                source.getByteSize(), source.getStorageKey(), source.getContentMd5());
        metadataCache.evictOwner(user.id());

        log.info("share saved to drive receiverId={} token={} sourceOwnerId={} newEntryId={}",
                user.id(), token, share.getOwnerId(), saved.getId());
        analysis.uploaded(saved);
        return saved;
    }

    /* ---------------- 内部 ---------------- */

    /**
     * 校验分享有效性：token 存在、未过期、提取码正确。
     *
     * <p>三种失败原因合并成同一个异常并统一映射为 404，是有意的：
     * 如果分别返回"分享不存在"和"提取码错误"，攻击者就能用响应差异
     * 先枚举出哪些 token 是有效的，再集中猜提取码。</p>
     */
    private FileShareRow requireValidShare(String token, String passcode) {
        FileShareRow share = shares.findByToken(token).orElseThrow(ShareAccessException::new);
        boolean expired = share.getExpiresAt() != null
                && !share.getExpiresAt().isAfter(LocalDateTime.now());
        if (expired || passcode == null || !passwords.matches(passcode, share.getPasscodeHash())) {
            throw new ShareAccessException();
        }
        return share;
    }

    private FileDelivery delivery(StoredDocumentRow document, String range) throws IOException {
        long size = document.getByteSize();
        long[] bounds = parse(range, size);
        long start = bounds[0];
        long end = bounds[1];
        InputStream input = objects.open(document.getStorageKey(), start, end - start + 1);
        return new FileDelivery(document.getDisplayName(), size, start, end, input);
    }

    /** 返回 [start, end] 闭区间。 */
    long[] parse(String header, long size) {
        if (size <= 0) {
            return new long[]{0, 0};
        }
        if (header == null || header.isBlank()) {
            return new long[]{0, size - 1};
        }
        if (!header.startsWith("bytes=") || header.contains(",")) {
            throw new IllegalArgumentException("only one bytes range is supported");
        }
        String[] parts = header.substring("bytes=".length()).split("-", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("malformed range header");
        }

        try {
            if (parts[0].isEmpty()) {
                // suffix range：bytes=-500 表示最后 500 字节
                long suffixLength = Long.parseLong(parts[1]);
                if (suffixLength <= 0) {
                    throw new IllegalArgumentException("malformed suffix range");
                }
                long start = Math.max(0, size - suffixLength);
                return new long[]{start, size - 1};
            }

            long start = Long.parseLong(parts[0]);
            long end = parts[1].isEmpty() ? size - 1 : Long.parseLong(parts[1]);
            if (start < 0 || start >= size || end < start) {
                throw new IllegalArgumentException("range is outside the object");
            }
            // 上界夹到文件末尾：播放器常常请求一个超出实际大小的 end。
            return new long[]{start, Math.min(end, size - 1)};
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("malformed range header", exception);
        }
    }

    public record FileDelivery(String displayName, long totalSize,
                               long start, long end, InputStream input) {
    }

    public record ShareInfo(String displayName, long byteSize, LocalDateTime expiresAt) {
    }
}
