package com.jmr.jdms.access.web;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.Subject;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's tenant-wide permissions, so the UI can hide what the caller cannot do (B-08). A hint only: every
 * request is authorised again on the server. Decided without audit (nothing is attempted).
 */
@RestController
class PermissionsController {

    /** Tenant-wide actions; document actions are reported per document ({@code allowedActions}). */
    record Permissions(List<Action> actions) {
    }

    private final AccessPolicy policy;

    PermissionsController(AccessPolicy policy) {
        this.policy = policy;
    }

    @GetMapping("/api/v1/me/permissions")
    @PreAuthorize("isAuthenticated()")
    Permissions permissions() {
        Subject subject = Subject.current();
        ResourceRef.Tenant tenant = new ResourceRef.Tenant(subject.tenantId());
        return new Permissions(Arrays.stream(Action.values())
                .filter(action -> policy.decide(subject, action, tenant).allowed()).toList());
    }
}
