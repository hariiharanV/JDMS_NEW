package com.jmr.jdms.document.domain;

/** Version approval (spec §5). Drafts are minor versions uploaded while checked out. */
public enum ApprovalStatus {
    DRAFT,
    PENDING,
    APPROVED,
    REJECTED
}
