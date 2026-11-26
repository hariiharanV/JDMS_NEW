package com.jmr.jdms.audit.web;

import com.jmr.jdms.audit.api.AuditEventView;
import com.jmr.jdms.audit.api.AuditOutcome;
import com.jmr.jdms.audit.api.AuditVerificationView;
import com.jmr.jdms.audit.domain.AuditQueryService;
import com.jmr.jdms.audit.domain.AuditVerifier;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's audit trail (API outline "Audit"): query with filters and hash-chain verification. Requires
 * {@code VIEW_AUDIT}; every query is itself audited.
 */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("@access.can('VIEW_AUDIT')")
class AuditController {

    static final String VERIFICATION_NOT_FOUND = "AUDIT_VERIFICATION_NOT_FOUND";

    private final AuditQueryService queries;
    private final AuditVerifier verifier;

    AuditController(AuditQueryService queries, AuditVerifier verifier) {
        this.queries = queries;
        this.verifier = verifier;
    }

    @GetMapping
    PageResponse<AuditEventView> search(@RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId, @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action, @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(queries.search(new AuditQueryService.Filter(resourceType, resourceId, actorId, action,
                outcome, from, to), page, size), view -> view);
    }

    /** The latest verification result of the tenant's chain. */
    @GetMapping("/verification")
    AuditVerificationView latestVerification() {
        return verifier.latest().orElseThrow(() -> ApiException.notFound(VERIFICATION_NOT_FOUND,
                "The audit trail has not been verified yet."));
    }

    /** Verifies the chain now (also runs daily); repeating it is harmless. */
    @PostMapping("/verification")
    AuditVerificationView verify(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return verifier.verify(AuditVerificationView.Trigger.REQUEST);
    }
}
