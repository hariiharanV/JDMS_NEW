package com.jmr.jdms.identity.web;

import com.jmr.jdms.identity.domain.DelegationAdministration;
import com.jmr.jdms.identity.domain.DelegationAdministration.DelegationInput;
import com.jmr.jdms.identity.domain.DelegationAdministration.DelegationView;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /admin/delegations}: any user proposes delegating their own roles (202, approved by a security admin);
 * delegations are listed to the parties and to security admins; revocation is immediate. Rules in the service.
 */
@RestController
@RequestMapping("/api/v1/admin/delegations")
@PreAuthorize("isAuthenticated()")
class DelegationController {

    private final DelegationAdministration delegations;

    DelegationController(DelegationAdministration delegations) {
        this.delegations = delegations;
    }

    @GetMapping
    List<DelegationView> list() {
        return delegations.list();
    }

    @PostMapping
    ResponseEntity<PendingChangeView> propose(@RequestBody DelegationInput input,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        PendingChangeView change = delegations.propose(input);
        return ResponseEntity.accepted().location(URI.create("/api/v1/admin/pending-changes/" + change.id()))
                .body(change);
    }

    @PostMapping("/{id}/revoke")
    DelegationView revoke(@PathVariable UUID id) {
        return delegations.revoke(id);
    }
}
