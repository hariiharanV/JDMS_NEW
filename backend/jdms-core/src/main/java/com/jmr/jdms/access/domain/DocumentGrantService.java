package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.DocumentGrants;
import com.jmr.jdms.access.api.GrantSource;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.AccessRulesChanged;
import com.jmr.jdms.shared.tenant.TenantScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and revokes document grants (used by retrieval requests and shares); every change is audited. */
@Service
class DocumentGrantService implements DocumentGrants {

    private final DocumentGrantRepository grants;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;

    DocumentGrantService(DocumentGrantRepository grants, AuditTrail auditTrail, ApplicationEventPublisher events) {
        this.grants = grants;
        this.auditTrail = auditTrail;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID grant(UUID documentId, UUID userId, Set<Action> actions, Instant validUntil, GrantSource source,
            UUID sourceId) {
        DocumentGrant grant = grants.save(new DocumentGrant(documentId, userId, actions,
                validUntil.truncatedTo(ChronoUnit.MICROS), source, sourceId));
        auditTrail.record(AuditEntry.success("DOCUMENT_GRANT_CREATED", "DOCUMENT", documentId, Map.of(
                "grantId", grant.getId().toString(), "userId", userId.toString(),
                "actions", actions.stream().map(Enum::name).sorted().toList(), "validUntil", validUntil.toString(),
                "source", source.name())));
        events.publishEvent(new AccessRulesChanged(TenantScope.currentTenantId().orElseThrow()));
        return grant.getId();
    }

    @Override
    @Transactional
    public void revoke(UUID grantId) {
        grants.findById(grantId).ifPresent(grant -> {
            grant.revoke(Instant.now().truncatedTo(ChronoUnit.MICROS));
            auditTrail.record(AuditEntry.success("DOCUMENT_GRANT_REVOKED", "DOCUMENT", grant.getDocumentId(),
                    Map.of("grantId", grantId.toString())));
            events.publishEvent(new AccessRulesChanged(TenantScope.currentTenantId().orElseThrow()));
        });
    }
}
