package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.Decision;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditOutcome;
import com.jmr.jdms.audit.api.AuditTrail;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Audits denied access in its own transaction, so the record survives the failed request. */
@Component
class DenialAuditor {

    private final AuditTrail auditTrail;

    DenialAuditor(AuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(Action action, ResourceRef resource, Decision decision) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action.name());
        details.put("reason", decision.reason().name());
        if (decision.decidedBy() != null) {
            details.put("ruleId", decision.decidedBy().toString());
        }
        String type = switch (resource) {
            case ResourceRef.Tenant tenant -> "TENANT";
            case ResourceRef.NewDocument document -> "DOCUMENT_TYPE";
            case ResourceRef.Document document -> "DOCUMENT";
        };
        String id = switch (resource) {
            case ResourceRef.Tenant tenant -> tenant.tenantId().toString();
            case ResourceRef.NewDocument document -> document.documentTypeId().toString();
            case ResourceRef.Document document -> document.documentId().toString();
        };
        auditTrail.record(new AuditEntry("ACCESS_DENIED", type, id, AuditOutcome.DENIED, details, null, null));
    }
}
