package com.mengde.clouddrive.analysis;

import com.mengde.clouddrive.auth.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analysis")
public class FileAnalysisController {
    private final FileAnalysisService analysis;
    public FileAnalysisController(FileAnalysisService analysis) { this.analysis = analysis; }

    @GetMapping
    public List<FileAnalysisResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(name = "entryIds", required = false) List<Long> ids) {
        return analysis.list(user.id(), ids);
    }

    @GetMapping("/{entryId}")
    public FileAnalysisResponse find(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("entryId") long entryId) {
        return analysis.find(user.id(), entryId);
    }

    @PostMapping("/{entryId}/reanalyze")
    public ResponseEntity<FileAnalysisResponse> reanalyze(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("entryId") long entryId) {
        return ResponseEntity.accepted().body(analysis.schedule(user.id(), entryId));
    }
}
