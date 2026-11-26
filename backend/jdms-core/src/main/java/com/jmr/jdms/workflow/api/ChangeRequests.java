package com.jmr.jdms.workflow.api;

/** Submits administrative changes for maker-checker approval. */
public interface ChangeRequests {

    /**
     * Stores the change as {@code PENDING} and audits the proposal. Fails with 409 {@code CHANGE_PENDING} when the
     * subject or its key already has a pending change.
     */
    PendingChangeView submit(ChangeProposal proposal);
}
