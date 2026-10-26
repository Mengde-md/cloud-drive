package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.DriveEntryApplicationService;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 条目资源 /api/entries（Day06 建立契约，Day12 接数据库，Day16 加隔离，
 * Day20 加缓存，Day22 加回收站与分页）。
 *
 * <p>身份一律通过 {@code @AuthenticationPrincipal} 注入，接口上不出现 ownerId 参数。</p>
 *
 * <p>缓存的读写位置有讲究：读路径把"回源函数"交给缓存对象（Cache Aside 的 read-through 写法），
 * 写路径在业务成功之后才 evict。顺序反过来（先 evict 再写库）会留下一个窗口：
 * 失效之后、提交之前的读请求会把旧数据重新加载进缓存，然后长期停留。</p>
 */
@RestController
@RequestMapping("/api/entries")
public class DriveEntryController {

    private final DriveEntryApplicationService entryService;
    private final PrivateEntryMetadataCache cache;

    public DriveEntryController(DriveEntryApplicationService entryService,
                                PrivateEntryMetadataCache cache) {
        this.entryService = entryService;
        this.cache = cache;
    }

    @GetMapping
    public List<EntryResponse> listAll(@AuthenticationPrincipal AuthenticatedUser user) {
        return cache.list(user.id(), () -> entryService.list(user).stream()
                .map(EntryResponse::from)
                .toList());
    }

    /** Day22：分页列表。分页结果不进缓存——页码组合太多，缓存命中率低且失效成本高。 */
    @GetMapping("/page")
    public EntryPageResponse page(@AuthenticationPrincipal AuthenticatedUser user,
                                  @RequestParam(name = "page", defaultValue = "0") int page,
                                  @RequestParam(name = "size", defaultValue = "20") int size) {
        return entryService.page(user, page, size);
    }

    @GetMapping("/{id}")
    public EntryResponse find(@AuthenticationPrincipal AuthenticatedUser user,
                              @PathVariable("id") long id) {
        return cache.entry(user.id(), id, () -> EntryResponse.from(entryService.find(user, id)));
    }

    @PostMapping
    public ResponseEntity<EntryResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                @Valid @RequestBody EntryCreateRequest request) {
        DriveEntry created = entryService.create(user, request.parentId(), request.displayName(),
                request.kindValue(), request.effectiveByteSize());
        cache.evictOwner(user.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.from(created));
    }

    @PutMapping("/{id}/name")
    public ResponseEntity<Void> rename(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable("id") long id,
                                       @Valid @RequestBody RenameRequest request) {
        entryService.rename(user, id, request.displayName());
        cache.evictEntry(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 删除（进回收站）。
     *
     * <p>返回 200 而不是 204，是因为级联删除后"到底删了多少项"是用户需要知道的信息：
     * 删一个目录可能连带几十个文件，界面得能如实告诉他。</p>
     */
    @DeleteMapping("/{id}")
    public AffectedResponse delete(@AuthenticationPrincipal AuthenticatedUser user,
                                   @PathVariable("id") long id) {
        int affected = entryService.delete(user, id);
        // 级联删除会影响子树里的每一个条目，单独失效某一个键不够，整个 owner 的缓存都要清
        cache.evictEntry(user.id(), id);
        cache.evictOwner(user.id());
        return new AffectedResponse(affected);
    }

    /* ---------------- Day22：回收站 ---------------- */

    @GetMapping("/trash")
    public TrashPageResponse trash(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestParam(name = "page", defaultValue = "0") int page,
                                   @RequestParam(name = "size", defaultValue = "20") int size) {
        return entryService.trash(user, page, size);
    }

    @PostMapping("/trash/{id}/restore")
    public AffectedResponse restore(@AuthenticationPrincipal AuthenticatedUser user,
                                    @PathVariable("id") long id) {
        int affected = entryService.restore(user, id);
        cache.evictEntry(user.id(), id);
        cache.evictOwner(user.id());
        return new AffectedResponse(affected);
    }

    @DeleteMapping("/trash")
    public PurgeResponse purge(@AuthenticationPrincipal AuthenticatedUser user) {
        int purged = entryService.purgeTrash(user);
        cache.evictOwner(user.id());
        return new PurgeResponse(purged);
    }

    /** 受影响的条目数。级联删除/恢复时这个数字可能远大于 1。 */
    public record AffectedResponse(int affectedCount) {
    }

    public record PurgeResponse(int purgedCount) {
    }
}
