package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.OwnedDriveEntryApplicationService;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.domain.DriveEntry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;

/**
 * 条目资源 (/api/entries) 的 HTTP 契约：
 * 200 列表/详情、201 创建、204 重命名；4xx/5xx 由全局异常处理器统一给出。
 */
@RestController
@RequestMapping("/api/entries")
public class DriveEntryController {

    private final OwnedDriveEntryApplicationService entryService;
    private final PrivateEntryMetadataCache cache;

    public DriveEntryController(OwnedDriveEntryApplicationService entryService, PrivateEntryMetadataCache cache) {
        this.entryService = entryService; this.cache = cache;
    }

    @GetMapping
    public List<EntryResponse> listAll(@AuthenticationPrincipal AuthenticatedUser user) {
        return cache.list(user.id(), () -> entryService.list(user).stream().map(EntryResponse::from).toList());
    }

    @GetMapping("/page")
    public EntryPageResponse page(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(name = "page", defaultValue = "0") int page, @RequestParam(name = "size", defaultValue = "20") int size) {
        return entryService.page(user, page, size);
    }

    @GetMapping("/trash")
    public TrashPageResponse trash(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(name = "page", defaultValue = "0") int page, @RequestParam(name = "size", defaultValue = "20") int size) {
        return entryService.trash(user, page, size);
    }

    @PostMapping("/trash/{id}/restore")
    public ResponseEntity<Void> restore(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        entryService.restore(user, id); cache.evictEntry(user.id(), id); return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/trash")
    public ResponseEntity<Void> purgeTrash(@AuthenticationPrincipal AuthenticatedUser user) {
        entryService.purgeTrash(user); cache.evictOwner(user.id()); return ResponseEntity.noContent().build();
    }

    @PostMapping
    public ResponseEntity<EntryResponse> create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody EntryCreateRequest request) {
        DriveEntry created = entryService.create(user, request);
        cache.evictOwner(user.id()); return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.from(created));
    }

    @GetMapping("/{id}")
    public EntryResponse find(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        return cache.entry(user.id(), id, () -> EntryResponse.from(entryService.find(user, id)));
    }

    @PutMapping("/{id}/name")
    public ResponseEntity<Void> rename(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id, @Valid @RequestBody RenameRequest request) {
        entryService.rename(user, id, request.displayName()); cache.evictEntry(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        entryService.delete(user, id); cache.evictEntry(user.id(), id);
        return ResponseEntity.noContent().build();
    }
}
