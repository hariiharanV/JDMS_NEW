package com.jmr.jdms.audit.domain;

import com.jmr.jdms.audit.api.ActorType;
import com.jmr.jdms.audit.api.AuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * An append-only audit record (legacy {@code CM_HISTORY}). Never updated or deleted by the application; each row's
 * {@code hash} covers its fields and the previous row's hash in the same tenant, so tampering breaks the chain.
 */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "chain_seq", nullable = false)
    private long chainSeq;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private ActorType actorType;

    @Column(name = "actor_id", nullable = false, length = 64)
    private String actorId;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AuditOutcome outcome;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String details;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    @Column(nullable = false, length = 64)
    private String hash;

    protected AuditEvent() {
    }

    AuditEvent(AuditRecordFields fields, long chainSeq, String prevHash, String hash) {
        this.id = fields.id();
        this.chainSeq = chainSeq;
        this.occurredAt = fields.occurredAt();
        this.actorType = fields.actorType();
        this.actorId = fields.actorId();
        this.action = fields.action();
        this.resourceType = fields.resourceType();
        this.resourceId = fields.resourceId();
        this.outcome = fields.outcome();
        this.clientIp = fields.clientIp();
        this.requestId = fields.requestId();
        this.details = fields.detailsJson();
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    /** Position in the tenant's chain: 1 for the first event. */
    public long getChainSeq() {
        return chainSeq;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getDetails() {
        return details;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }
}
