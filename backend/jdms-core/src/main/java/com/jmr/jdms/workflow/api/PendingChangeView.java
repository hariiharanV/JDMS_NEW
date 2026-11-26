package com.jmr.jdms.workflow.api;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A submitted change, as returned to proposers and approvers. */
public record PendingChangeView(
        UUID id,
        String subjectType,
        ChangeOperation operation,
        UUID subjectId,
        Long baseVersion,
        String summary,
        JsonNode payload,
        PendingChangeStatus status,
        UUID proposedBy,
        Instant proposedAt,
        UUID decidedBy,
        Instant decidedAt,
        String decisionComment) {
}
