package com.jmr.jdms.workflow.api;

import com.jmr.jdms.shared.context.RequestContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Applies approved changes of one subject type. Implemented by the module that owns the subject (metadata,
 * identity, access, document...), so the workflow module never depends on them.
 *
 * <p>{@link #canDecide} says whether the current caller may approve or reject the change (through the module's
 * access policy check); {@link #approvalBlocker} adds separation-of-duties rules beyond "not the proposer".
 *
 * <p>{@link #apply} runs in the approver's transaction and request; it must re-check everything that could have
 * changed since the proposal (base version, uniqueness, references) and return {@link ApplyResult#stale} rather
 * than overwrite newer data. It writes its own audit event for the applied change.
 */
public interface ChangeHandler {

    String subjectType();

    /**
     * Realm roles whose holders get the change in their task inbox. This is routing only: whoever decides is still
     * checked with {@link #canDecide} and {@link #approvalBlocker}.
     */
    Set<String> candidateRoles();

    /** The {@code approval_record.subject_type} recorded for this change's history. */
    default String historyType() {
        return "ADMIN_CHANGE";
    }

    /** The document the change concerns, if any (its history then shows the change). */
    default UUID documentId(PendingChangeView change) {
        return null;
    }

    boolean canDecide(PendingChangeView change);

    ApplyResult apply(PendingChangeView change);

    /** Why this approver may not approve this change (e.g. it changes their own access); empty when they may. */
    default Optional<String> approvalBlocker(PendingChangeView change, RequestContext approver) {
        return Optional.empty();
    }
}
