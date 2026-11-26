package com.jmr.jdms.workflow.web;

import com.jmr.jdms.shared.error.Problems;
import com.jmr.jdms.shared.web.PageResponse;
import com.jmr.jdms.workflow.api.PendingChangeStatus;
import com.jmr.jdms.workflow.api.PendingChangeView;
import com.jmr.jdms.workflow.domain.Decision;
import com.jmr.jdms.workflow.domain.PendingChangeService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maker-checker inbox ({@code GET /admin/pending-changes} in {@code docs/04-api-outline.md}). Each caller sees the
 * changes they may decide (per the owning module's access policy check) and those they proposed; decisions are
 * authorised per change in the service.
 */
@RestController
@RequestMapping("/api/v1/admin/pending-changes")
@PreAuthorize("isAuthenticated()")
class PendingChangeController {

    private final PendingChangeService service;

    PendingChangeController(PendingChangeService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<PendingChangeResponse> list(@RequestParam(required = false) PendingChangeStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(service.list(status, PageRequest.of(page, size)), this::response);
    }

    @GetMapping("/{id}")
    PendingChangeResponse get(@PathVariable UUID id) {
        return response(service.get(id));
    }

    /** 200 when applied; 409 {@code CHANGE_STALE} when it could not be applied (the change is then STALE). */
    @PostMapping("/{id}/approve")
    ResponseEntity<?> approve(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest request) {
        Decision decision = service.approve(id, request == null ? null : request.comment());
        if (decision.staleReason() != null) {
            ProblemDetail problem = Problems.of(HttpStatus.CONFLICT, PendingChangeService.CHANGE_STALE,
                    decision.staleReason());
            problem.setProperty("change", response(decision.change()));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
        return ResponseEntity.ok(response(decision.change()));
    }

    @PostMapping("/{id}/reject")
    PendingChangeResponse reject(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest request) {
        return response(service.reject(id, request == null ? null : request.comment()));
    }

    @PostMapping("/{id}/cancel")
    PendingChangeResponse cancel(@PathVariable UUID id) {
        return response(service.cancel(id));
    }

    record DecisionRequest(@Size(max = 1000) String comment) {
    }

    private PendingChangeResponse response(PendingChangeView change) {
        return PendingChangeResponse.of(change, service.canDecide(change));
    }
}
