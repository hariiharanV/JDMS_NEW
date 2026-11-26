package com.jmr.jdms.search.web;

import com.jmr.jdms.search.domain.SearchAdministration;
import com.jmr.jdms.search.domain.SearchService;
import com.jmr.jdms.search.domain.SearchService.Query;
import com.jmr.jdms.search.domain.SearchService.Result;
import com.jmr.jdms.search.domain.SearchUnavailableException;
import com.jmr.jdms.shared.error.Problems;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /search}: open to every authenticated user; results are trimmed to what each caller may see.
 * {@code POST /admin/search/rebuild}: tenant admins rebuild their tenant's index from the database.
 */
@RestController
class SearchController {

    public static final String SEARCH_UNAVAILABLE = "SEARCH_UNAVAILABLE";
    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final SearchService search;
    private final SearchAdministration administration;

    SearchController(SearchService search, SearchAdministration administration) {
        this.search = search;
        this.administration = administration;
    }

    @PostMapping("/api/v1/search")
    @PreAuthorize("isAuthenticated()")
    Result search(@RequestBody Query query) {
        return search.search(query);
    }

    /** 202 with the job, visible under {@code /admin/jobs}. */
    @PostMapping("/api/v1/admin/search/rebuild")
    @PreAuthorize("@access.can('ADMIN_METADATA')")
    ResponseEntity<Map<String, UUID>> rebuild(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID job = administration.requestRebuild();
        return ResponseEntity.accepted().location(URI.create("/api/v1/admin/jobs?status=READY"))
                .body(Map.of("jobId", job));
    }

    @ExceptionHandler(SearchUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(SearchUnavailableException e) {
        log.warn("Search unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Problems.of(HttpStatus.SERVICE_UNAVAILABLE,
                SEARCH_UNAVAILABLE, "Search is temporarily unavailable."));
    }
}
