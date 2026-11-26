package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import com.jmr.jdms.workflow.api.ApprovalDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One entry of the approval history (spec §4.4): who, when, decision, comment, stage. Never changed. */
@Entity
@Table(name = "approval_record")
public class ApprovalRecord extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "subject_type", nullable = false, length = 30)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "process_instance_id", nullable = false, length = 64)
    private String processInstanceId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "round_no", nullable = false)
    private int round;

    @Column(name = "stage_key", length = 100)
    private String stageKey;

    @Column(name = "stage_name", length = 200)
    private String stageName;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "actor_name", nullable = false, length = 200)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApprovalDecision decision;

    @Column(name = "decision_comment", length = 2000)
    private String comment;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected ApprovalRecord() {
    }

    ApprovalRecord(ProcessSubject subject, String taskId, int round, String stageKey, String stageName, UUID actorId,
            String actorName, ApprovalDecision decision, String comment, Instant decidedAt) {
        this.id = Ids.newId();
        this.documentId = subject.documentId();
        this.versionId = subject.versionId();
        this.subjectType = subject.historyType();
        this.subjectId = subject.subjectId();
        this.processInstanceId = subject.processInstanceId();
        this.taskId = taskId;
        this.round = round;
        this.stageKey = stageKey;
        this.stageName = stageName;
        this.actorId = actorId;
        this.actorName = actorName;
        this.decision = decision;
        this.comment = comment;
        this.decidedAt = decidedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getRound() {
        return round;
    }

    public String getStageKey() {
        return stageKey;
    }

    public String getStageName() {
        return stageName;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public ApprovalDecision getDecision() {
        return decision;
    }

    public String getComment() {
        return comment;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
