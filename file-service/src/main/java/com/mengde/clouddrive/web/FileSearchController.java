package com.mengde.clouddrive.web;

import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.persistence.FileSearchMapper;
import com.mengde.clouddrive.search.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files/search")
public class FileSearchController {
    private final FileSearchService search;
    public FileSearchController(FileSearchService search) { this.search = search; }

    @GetMapping
    public FileSearchService.SearchPage search(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("q") String q, @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return search.search(user.id(), new SearchQuery(q, page, size));
    }

    @PostMapping("/reindex")
    public FileSearchMapper.State reindex(@AuthenticationPrincipal AuthenticatedUser user) { return search.reindex(user.id()); }

    @ExceptionHandler(SearchUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse unavailable(SearchUnavailableException failure) {
        return new ErrorResponse("search index unavailable", failure.getMessage());
    }
    public record ErrorResponse(String message, String reason) { }
}
