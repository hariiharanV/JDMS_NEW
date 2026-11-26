package com.jmr.jdms.workflow.api;

import java.util.UUID;

/** Looks up users for committee membership; implemented by the identity module. */
public interface ApproverDirectory {

    /** Whether the user exists in the current tenant and is active. */
    boolean isActiveUser(UUID userId);

    /** A user who could be named on a committee: active, of the current tenant, with their mirrored roles. */
    record Candidate(UUID id, String username, String displayName, java.util.List<String> roles) {
    }

    /** The active users of the current tenant, by username (committee member pickers, B-17). */
    java.util.List<Candidate> activeUsers();
}
