package com.jmr.jdms.workflow.web;

import com.jmr.jdms.shared.web.ETags;
import com.jmr.jdms.shared.web.PageResponse;
import com.jmr.jdms.workflow.api.ApproverDirectory;
import com.jmr.jdms.workflow.api.PendingChangeView;
import com.jmr.jdms.workflow.domain.CommitteeAdministration;
import com.jmr.jdms.workflow.domain.CommitteeAdministration.CommitteePayload;
import com.jmr.jdms.workflow.domain.CommitteeAdministration.CommitteeView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /admin/committees}: committees and their members for {@code committee-approval}. Writes are proposals
 * answered with 202; a second tenant admin approves them under {@code /admin/pending-changes} or {@code /tasks}.
 */
@RestController
@RequestMapping("/api/v1/admin/committees")
@PreAuthorize("@access.can('ADMIN_METADATA')")
class CommitteeAdminController {

    private final CommitteeAdministration committees;
    private final ApproverDirectory directory;

    CommitteeAdminController(CommitteeAdministration committees, ApproverDirectory directory) {
        this.committees = committees;
        this.directory = directory;
    }

    /** The tenant's active users with their roles, to name committee members (B-17). */
    @GetMapping("/candidates")
    List<ApproverDirectory.Candidate> candidates() {
        return directory.activeUsers();
    }

    @GetMapping
    PageResponse<CommitteeView> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(committees.list(PageRequest.of(page, size, Sort.by("code"))), view -> view);
    }

    @GetMapping("/{id}")
    ResponseEntity<CommitteeView> get(@PathVariable UUID id) {
        CommitteeView view = committees.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping
    ResponseEntity<PendingChangeView> create(@RequestBody CommitteePayload request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return accepted(committees.proposeCreate(request));
    }

    @PutMapping("/{id}")
    ResponseEntity<PendingChangeView> update(@PathVariable UUID id, @RequestBody CommitteePayload request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return accepted(committees.proposeUpdate(id, ETags.requiredVersion(ifMatch), request));
    }

    /** Deactivates: past approvals refer to committees, so they are never deleted. */
    @DeleteMapping("/{id}")
    ResponseEntity<PendingChangeView> deactivate(@PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return accepted(committees.proposeDeactivate(id, ETags.requiredVersion(ifMatch)));
    }

    private static ResponseEntity<PendingChangeView> accepted(PendingChangeView change) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/admin/pending-changes/" + change.id()))
                .body(change);
    }
}
