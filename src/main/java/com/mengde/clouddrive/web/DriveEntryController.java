package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.DriveEntryApplicationService;
import com.mengde.clouddrive.domain.DriveEntry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 条目资源 (/api/entries) 的 HTTP 契约：
 * 200 列表/详情、201 创建、204 重命名；4xx/5xx 由全局异常处理器统一给出。
 */
@RestController
@RequestMapping("/api/entries")
public class DriveEntryController {

    private final DriveEntryApplicationService entryService;

    public DriveEntryController(DriveEntryApplicationService entryService) {
        this.entryService = entryService;
    }

    @GetMapping
    public List<EntryResponse> listAll() {
        return entryService.listAll().stream().map(EntryResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<EntryResponse> create(@Valid @RequestBody EntryCreateRequest request) {
        DriveEntry created = entryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.from(created));
    }

    @GetMapping("/{id}")
    public EntryResponse find(@PathVariable("id") long id) {
        return EntryResponse.from(entryService.findRequired(id));
    }

    @PutMapping("/{id}/name")
    public ResponseEntity<Void> rename(@PathVariable("id") long id, @Valid @RequestBody RenameRequest request) {
        entryService.rename(id, request.displayName());
        return ResponseEntity.noContent().build();
    }
}
