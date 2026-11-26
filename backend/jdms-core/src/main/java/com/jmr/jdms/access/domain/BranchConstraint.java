package com.jmr.jdms.access.domain;

/**
 * Which documents' branches an ALLOW rule covers: {@code OWN} the user's branch scope, {@code ALL} any branch,
 * {@code LIST} the listed branches. DENY rules apply whatever the branch.
 */
public enum BranchConstraint {
    OWN,
    ALL,
    LIST
}
