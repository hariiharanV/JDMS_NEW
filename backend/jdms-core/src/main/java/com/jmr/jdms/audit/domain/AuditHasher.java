package com.jmr.jdms.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Computes the hash chain value of an audit event: SHA-256 over an unambiguous encoding of every field plus the
 * previous event's hash. Each field is written as {@code <length>:<value>;} so no value can be confused with a
 * separator. The verification job (B-11) recomputes hashes with this class.
 */
final class AuditHasher {

    /** {@code prev_hash} of a tenant's first event. */
    static final String GENESIS = "0".repeat(64);

    private static final JsonMapper CANONICAL_JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private AuditHasher() {
    }

    static String canonicalJson(Map<String, Object> details) {
        return CANONICAL_JSON.writeValueAsString(new TreeMap<>(details));
    }

    /**
     * Canonical form of stored details. PostgreSQL returns {@code jsonb} re-formatted, so verification hashes this,
     * never the raw column text.
     */
    @SuppressWarnings("unchecked")
    static String canonicalJson(String storedJson) {
        return canonicalJson(CANONICAL_JSON.readValue(storedJson, Map.class));
    }

    /** Recomputes the hash of a stored event, for chain verification. */
    static String recompute(AuditEvent event) {
        return hash(new AuditRecordFields(event.getId(), event.getTenantId(), event.getOccurredAt(),
                event.getActorType(), event.getActorId(), event.getAction(), event.getResourceType(),
                event.getResourceId(), event.getOutcome(), event.getClientIp(), event.getRequestId(),
                canonicalJson(event.getDetails())), event.getPrevHash());
    }

    static String hash(AuditRecordFields fields, String prevHash) {
        StringBuilder encoded = new StringBuilder(512);
        append(encoded, fields.id());
        append(encoded, fields.tenantId());
        append(encoded, fields.occurredAt());
        append(encoded, fields.actorType());
        append(encoded, fields.actorId());
        append(encoded, fields.action());
        append(encoded, fields.resourceType());
        append(encoded, fields.resourceId());
        append(encoded, fields.outcome());
        append(encoded, fields.clientIp());
        append(encoded, fields.requestId());
        append(encoded, fields.detailsJson());
        append(encoded, prevHash);
        return sha256(encoded.toString());
    }

    private static void append(StringBuilder encoded, Object value) {
        String text = value == null ? "" : value.toString();
        encoded.append(text.length()).append(':').append(text).append(';');
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
