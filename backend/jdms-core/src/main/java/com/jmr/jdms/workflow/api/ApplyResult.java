package com.jmr.jdms.workflow.api;

/**
 * What happened when an approved change was applied. A stale result is not an error of the approver: the change is
 * marked {@code STALE} (and that is committed) and the approver is told why.
 */
public record ApplyResult(boolean applied, String staleReason) {

    public static ApplyResult ok() {
        return new ApplyResult(true, null);
    }

    public static ApplyResult stale(String reason) {
        return new ApplyResult(false, reason);
    }
}
