package com.jmr.jdms.audit.domain;

import com.jmr.jdms.audit.api.ActorType;
import com.jmr.jdms.audit.api.AuditOutcome;
import java.time.Instant;
import java.util.UUID;

/** The hashed fields of one audit event; {@code detailsJson} is canonical JSON (keys sorted, no whitespace). */
record AuditRecordFields(
        UUID id,
        UUID tenantId,
        Instant occurredAt,
        ActorType actorType,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        AuditOutcome outcome,
        String clientIp,
        String requestId,
        String detailsJson) {
}
