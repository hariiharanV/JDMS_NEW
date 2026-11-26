package com.jmr.jdms.workflow.api;

import java.time.Instant;
import java.util.UUID;

/** One entry of an item's approval history: who decided what, when, at which stage and why. */
public record ApprovalRecordView(UUID id, String subjectType, UUID subjectId, UUID versionId, String processKey,
        int round, String stageKey, String stageName, UUID actorId, String actorName, ApprovalDecision decision,
        String comment, Instant decidedAt) {
}
