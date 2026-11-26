package com.jmr.jdms.workflow.api;

import java.util.Objects;
import java.util.UUID;

/**
 * A change to submit for approval.
 *
 * @param subjectType the {@link ChangeHandler#subjectType()} that will apply it
 * @param subjectId   the subject's ID; for {@code CREATE}, the ID the new object will get
 * @param subjectKey  the natural key the change claims (e.g. a code being created), or null; at most one pending
 *                    change per key
 * @param baseVersion the subject's {@code version_lock} when proposed (null for {@code CREATE})
 * @param payload     the proposed values; serialised to JSON and given back to the handler on approval. Never
 *                    document content or secrets.
 * @param summary     one line for approvers, e.g. "Create cabinet CUSTOMERS"
 */
public record ChangeProposal(
        String subjectType,
        ChangeOperation operation,
        UUID subjectId,
        String subjectKey,
        Long baseVersion,
        Object payload,
        String summary) {

    public ChangeProposal {
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(summary, "summary");
    }
}
