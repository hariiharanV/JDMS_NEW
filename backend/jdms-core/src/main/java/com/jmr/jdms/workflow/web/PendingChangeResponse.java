package com.jmr.jdms.workflow.web;

import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.PendingChangeStatus;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A pending change in API responses; {@code canDecide} tells the caller whether they may approve or reject it. */
public record PendingChangeResponse(
        UUID id,
        String subjectType,
        ChangeOperation operation,
        UUID subjectId,
        String summary,
        JsonNode payload,
        PendingChangeStatus status,
        UUID proposedBy,
        Instant proposedAt,
        UUID decidedBy,
        Instant decidedAt,
        String decisionComment,
        boolean canDecide) {

    public static PendingChangeResponse of(PendingChangeView change, boolean canDecide) {
        return new PendingChangeResponse(change.id(), change.subjectType(), change.operation(), change.subjectId(),
                change.summary(), change.payload(), change.status(), change.proposedBy(), change.proposedAt(),
                change.decidedBy(), change.decidedAt(), change.decisionComment(), canDecide);
    }
}
