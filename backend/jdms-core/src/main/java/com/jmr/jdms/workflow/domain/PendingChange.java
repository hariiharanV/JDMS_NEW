package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.PendingChangeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A proposed administrative change awaiting a second user's decision (maker-checker). */
@Entity
@Table(name = "pending_change")
public class PendingChange extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "subject_type", nullable = false, length = 30)
    private String subjectType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChangeOperation operation;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "subject_key", length = 300)
    private String subjectKey;

    @Column(name = "base_version")
    private Long baseVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(nullable = false, length = 500)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PendingChangeStatus status;

    @Column(name = "proposed_by", nullable = false)
    private UUID proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_comment", length = 1000)
    private String decisionComment;

    @Column(name = "process_instance_id", length = 64)
    private String processInstanceId;

    protected PendingChange() {
    }

    PendingChange(String subjectType, ChangeOperation operation, UUID subjectId, String subjectKey, Long baseVersion,
            String payload, String summary, UUID proposedBy, Instant proposedAt) {
        this.id = Ids.newId();
        this.subjectType = subjectType;
        this.operation = operation;
        this.subjectId = subjectId;
        this.subjectKey = subjectKey;
        this.baseVersion = baseVersion;
        this.payload = payload;
        this.summary = summary;
        this.status = PendingChangeStatus.PENDING;
        this.proposedBy = proposedBy;
        this.proposedAt = proposedAt;
    }

    void attachProcess(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    void decide(PendingChangeStatus outcome, UUID decider, Instant at, String comment) {
        if (status != PendingChangeStatus.PENDING) {
            throw new IllegalStateException("Change " + id + " is already " + status);
        }
        this.status = outcome;
        this.decidedBy = decider;
        this.decidedAt = at;
        this.decisionComment = comment;
    }

    public UUID getId() {
        return id;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public ChangeOperation getOperation() {
        return operation;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getSubjectKey() {
        return subjectKey;
    }

    public Long getBaseVersion() {
        return baseVersion;
    }

    public String getPayload() {
        return payload;
    }

    public String getSummary() {
        return summary;
    }

    public PendingChangeStatus getStatus() {
        return status;
    }

    public UUID getProposedBy() {
        return proposedBy;
    }

    public Instant getProposedAt() {
        return proposedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }
}
