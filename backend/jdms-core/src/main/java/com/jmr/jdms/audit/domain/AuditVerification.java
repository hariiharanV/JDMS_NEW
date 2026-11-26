package com.jmr.jdms.audit.domain;

import com.jmr.jdms.audit.api.AuditVerificationView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/** The result of one verification run over a tenant's hash chain. Append-only, like the audit events. */
@Entity
@Immutable
@Table(name = "audit_verification")
public class AuditVerification {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 10)
    private AuditVerificationView.Trigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AuditVerificationView.Status status;

    @Column(name = "events_checked", nullable = false)
    private long eventsChecked;

    @Column(name = "head_seq", nullable = false)
    private long headSeq;

    @Column(name = "head_hash", nullable = false, length = 64)
    private String headHash;

    @Column(name = "broken_seq")
    private Long brokenSeq;

    @Column(name = "broken_event_id")
    private UUID brokenEventId;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AuditVerificationView.Failure failure;

    protected AuditVerification() {
    }

    AuditVerification(UUID id, Instant startedAt, Instant finishedAt, AuditVerificationView.Trigger trigger,
            long eventsChecked, long headSeq, String headHash, Long brokenSeq, UUID brokenEventId,
            AuditVerificationView.Failure failure) {
        this.id = id;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.trigger = trigger;
        this.status = failure == null ? AuditVerificationView.Status.VALID : AuditVerificationView.Status.BROKEN;
        this.eventsChecked = eventsChecked;
        this.headSeq = headSeq;
        this.headHash = headHash;
        this.brokenSeq = brokenSeq;
        this.brokenEventId = brokenEventId;
        this.failure = failure;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    AuditVerificationView view() {
        return new AuditVerificationView(id, startedAt, finishedAt, trigger, status, eventsChecked, headSeq,
                headHash, brokenSeq, brokenEventId, failure);
    }
}
