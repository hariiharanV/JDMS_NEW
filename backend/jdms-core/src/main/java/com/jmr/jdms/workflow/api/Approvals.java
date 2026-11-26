package com.jmr.jdms.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Starts approval processes and reads approval history. */
public interface Approvals {

    /**
     * Starts the process in the caller's transaction (the caller is the maker) and records the submission. Fails
     * with 409 {@code APPROVAL_ROUTING_FAILED} or {@code COMMITTEE_QUORUM_UNREACHABLE} when the routing tables or the
     * committee cannot produce approvers.
     *
     * @return the process instance ID
     */
    String start(ApprovalRequest request);

    /** The approval history of a document (all its subjects), oldest first. Callers check access to the document. */
    List<ApprovalRecordView> history(UUID documentId);

    /**
     * Records a legacy JDMS document's finished approval history (ADR-0020) in the caller's transaction: no process
     * runs, the records keep the legacy actors, decisions and times. Only the import of legacy documents uses this.
     *
     * @return the number of records written
     */
    int importHistory(UUID documentId, UUID versionId, List<ImportedDecision> decisions);

    /** One legacy workflow step; the actor is the legacy user name (legacy users have no JDMS identity). */
    record ImportedDecision(int round, String stageName, String actorName, ApprovalDecision decision, String comment,
            Instant decidedAt) {
    }
}
