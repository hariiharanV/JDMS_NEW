package com.jmr.jdms.audit.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A stored audit event as returned by the audit query API; {@code seq} is its position in the tenant's chain. */
public record AuditEventView(
        UUID id,
        long seq,
        Instant occurredAt,
        ActorType actorType,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        AuditOutcome outcome,
        String clientIp,
        String requestId,
        Map<String, Object> details,
        String prevHash,
        String hash) {
}
