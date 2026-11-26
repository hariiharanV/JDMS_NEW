package com.jmr.jdms.identity.domain;

/** Audit actions written by the identity module. */
final class IdentityAuditActions {

    static final String RESOURCE_USER = "USER_PROFILE";
    static final String PROFILE_CREATED = "USER_PROFILE_CREATED";
    static final String PROFILE_UPDATED = "USER_PROFILE_UPDATED";
    static final String ROLES_SYNCED = "USER_ROLES_SYNCED";
    static final String ACCESS_CHANGED = "USER_ACCESS_CHANGED";

    private IdentityAuditActions() {
    }
}
