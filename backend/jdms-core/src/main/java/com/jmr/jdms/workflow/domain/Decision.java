package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.workflow.api.PendingChangeView;

/** The result of approving a change: the change as now stored, and why it went stale if it did. */
public record Decision(PendingChangeView change, String staleReason) {
}
