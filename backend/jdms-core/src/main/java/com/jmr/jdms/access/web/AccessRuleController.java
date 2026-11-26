package com.jmr.jdms.access.web;

import com.jmr.jdms.access.domain.AccessRuleAdministration;
import com.jmr.jdms.access.domain.RuleInput;
import com.jmr.jdms.access.domain.RuleView;
import com.jmr.jdms.shared.web.ETags;
import com.jmr.jdms.shared.web.PageResponse;
import com.jmr.jdms.workflow.api.PendingChangeView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
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

/** {@code /admin/access-rules}: requires {@code ADMIN_ACCESS}; writes are proposals (202) for a second admin. */
@RestController
@RequestMapping("/api/v1/admin/access-rules")
@PreAuthorize("@access.can('ADMIN_ACCESS')")
class AccessRuleController {

    private final AccessRuleAdministration administration;

    AccessRuleController(AccessRuleAdministration administration) {
        this.administration = administration;
    }

    @GetMapping
    PageResponse<RuleView> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(administration.list(PageRequest.of(page, size, Sort.by("createdAt"))), view -> view);
    }

    @GetMapping("/{id}")
    ResponseEntity<RuleView> get(@PathVariable UUID id) {
        RuleView view = administration.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping
    ResponseEntity<PendingChangeView> create(@RequestBody RuleInput input,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return accepted(administration.proposeCreate(input));
    }

    @PutMapping("/{id}")
    ResponseEntity<PendingChangeView> update(@PathVariable UUID id, @RequestBody RuleInput input,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return accepted(administration.proposeUpdate(id, ETags.requiredVersion(ifMatch), input));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<PendingChangeView> delete(@PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return accepted(administration.proposeDelete(id, ETags.requiredVersion(ifMatch)));
    }

    private static ResponseEntity<PendingChangeView> accepted(PendingChangeView change) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/admin/pending-changes/" + change.id()))
                .body(change);
    }
}
