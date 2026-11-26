package com.jmr.jdms.identity.web;

import com.jmr.jdms.identity.domain.AccessChange;
import com.jmr.jdms.identity.domain.UserAccessAdministration;
import com.jmr.jdms.identity.domain.UserAccessView;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.web.ETags;
import com.jmr.jdms.shared.web.PageResponse;
import com.jmr.jdms.workflow.api.PendingChangeView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration for security admins: list users and set branch scope and clearance
 * ({@code /admin/users/{id}/scope} in {@code docs/04-api-outline.md}). Requires {@code ADMIN_ACCESS}.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("@access.can('ADMIN_ACCESS')")
class UserAdminController {

    private final UserAccessAdministration administration;

    UserAdminController(UserAccessAdministration administration) {
        this.administration = administration;
    }

    @GetMapping
    PageResponse<UserAccessView> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(administration.list(PageRequest.of(page, size, Sort.by("username"))), view -> view);
    }

    @GetMapping("/{id}")
    ResponseEntity<UserAccessView> get(@PathVariable UUID id) {
        UserAccessView view = administration.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    /**
     * Proposes a change of branch scope and clearance (202); a second security admin approves it under
     * {@code /admin/pending-changes}. Requires {@code If-Match} with the ETag from {@link #get}. Accepts
     * {@code Idempotency-Key}; the replay store arrives in B-03 (only one change per user can be pending).
     */
    @PutMapping("/{id}/scope")
    ResponseEntity<PendingChangeView> changeScope(@PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ScopeRequest request) {
        PendingChangeView change = administration.proposeAccessChange(id, ETags.requiredVersion(ifMatch),
                new AccessChange(request.allBranches(), request.branchCodes(), request.clearance()));
        return ResponseEntity.accepted().location(URI.create("/api/v1/admin/pending-changes/" + change.id()))
                .body(change);
    }

    record ScopeRequest(
            @NotNull Boolean allBranches,
            @Size(max = 500) List<@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,30}") String> branchCodes,
            @NotNull Classification clearance) {
    }
}
