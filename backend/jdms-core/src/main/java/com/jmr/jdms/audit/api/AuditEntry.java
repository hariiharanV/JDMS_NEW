package com.jmr.jdms.audit.api;

import java.util.Map;
import java.util.Objects;

/**
 * One audit event to record. Tenant, time, request ID and client IP are filled in by the audit trail. The actor is
 * the authenticated caller unless set explicitly (for example while the caller is still being authenticated).
 *
 * <p>{@code details} must never contain document content, tokens, keys or metadata values marked sensitive.
 */
public record AuditEntry(
        String action,
        String resourceType,
        String resourceId,
        AuditOutcome outcome,
        Map<String, Object> details,
        ActorType actorType,
        String actorId) {

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(outcome, "outcome");
        details = details == null ? Map.of() : Map.copyOf(details);
        if ((actorType == null) != (actorId == null)) {
            throw new IllegalArgumentException("actorType and actorId are set together");
        }
    }

    public static AuditEntry success(String action, String resourceType, Object resourceId, Map<String, Object> details) {
        return new AuditEntry(action, resourceType, String.valueOf(resourceId), AuditOutcome.SUCCESS, details, null,
                null);
    }

    public AuditEntry byActor(ActorType type, String id) {
        return new AuditEntry(action, resourceType, resourceId, outcome, details, type, id);
    }
}
