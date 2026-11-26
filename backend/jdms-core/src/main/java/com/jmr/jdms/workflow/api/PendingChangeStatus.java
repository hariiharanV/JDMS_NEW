package com.jmr.jdms.workflow.api;

/**
 * {@code PENDING} until decided. {@code STALE}: approved, but could not be applied because the subject changed (or
 * its code was taken) after the proposal; the proposer must propose again.
 */
public enum PendingChangeStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    STALE
}
