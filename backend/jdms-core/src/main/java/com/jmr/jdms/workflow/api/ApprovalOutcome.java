package com.jmr.jdms.workflow.api;

/** What an approval process reports to the subject's owner. */
public enum ApprovalOutcome {
    /** All stages approved (or the committee reached its quorum). */
    APPROVED,
    /** Rejected at a stage, or the committee can no longer reach its quorum. */
    REJECTED,
    /** Sent back to the maker with a comment. */
    RETURNED,
    /** The maker resubmitted after a return; the approval starts again from the first stage. */
    RESUBMITTED
}
