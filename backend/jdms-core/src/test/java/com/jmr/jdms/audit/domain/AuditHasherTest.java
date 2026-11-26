package com.jmr.jdms.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.audit.api.ActorType;
import com.jmr.jdms.audit.api.AuditOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditHasherTest {

    private final AuditRecordFields fields = new AuditRecordFields(UUID.randomUUID(), UUID.randomUUID(),
            Instant.parse("2026-10-24T12:00:00.123456Z"), ActorType.USER, "user-1", "ACTION", "TYPE", "res-1",
            AuditOutcome.SUCCESS, "10.0.0.1", "req-1", "{\"a\":1}");

    @Test
    void hashIsDeterministicSha256Hex() {
        assertThat(AuditHasher.hash(fields, AuditHasher.GENESIS))
                .isEqualTo(AuditHasher.hash(fields, AuditHasher.GENESIS))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void changingAnyFieldOrThePreviousHashChangesTheHash() {
        String original = AuditHasher.hash(fields, AuditHasher.GENESIS);

        assertThat(AuditHasher.hash(new AuditRecordFields(fields.id(), fields.tenantId(), fields.occurredAt(),
                fields.actorType(), "user-2", fields.action(), fields.resourceType(), fields.resourceId(),
                fields.outcome(), fields.clientIp(), fields.requestId(), fields.detailsJson()), AuditHasher.GENESIS))
                .isNotEqualTo(original);
        assertThat(AuditHasher.hash(fields, "1".repeat(64))).isNotEqualTo(original);
    }

    @Test
    void fieldBoundariesAreUnambiguous() {
        AuditRecordFields shifted = new AuditRecordFields(fields.id(), fields.tenantId(), fields.occurredAt(),
                fields.actorType(), "user-1A", "CTION", fields.resourceType(), fields.resourceId(), fields.outcome(),
                fields.clientIp(), fields.requestId(), fields.detailsJson());

        assertThat(AuditHasher.hash(shifted, AuditHasher.GENESIS))
                .isNotEqualTo(AuditHasher.hash(fields, AuditHasher.GENESIS));
    }

    @Test
    void canonicalJsonSortsKeysAtEveryLevel() {
        String json = AuditHasher.canonicalJson(Map.of("b", 1, "a", Map.of("y", List.of(2, 1), "x", "v")));

        assertThat(json).isEqualTo("{\"a\":{\"x\":\"v\",\"y\":[2,1]},\"b\":1}");
    }

    @Test
    void storedJsonbIsReCanonicalisedBeforeHashing() {
        String asWritten = AuditHasher.canonicalJson(Map.of("removed", List.of("MAKER"), "added", List.of("CHECKER")));
        String asPostgresReturnsIt = "{\"added\": [\"CHECKER\"], \"removed\": [\"MAKER\"]}";

        assertThat(AuditHasher.canonicalJson(asPostgresReturnsIt)).isEqualTo(asWritten);
    }
}
