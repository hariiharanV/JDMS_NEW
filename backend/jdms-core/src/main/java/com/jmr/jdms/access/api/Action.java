package com.jmr.jdms.access.api;

import java.util.EnumSet;
import java.util.Set;

/** Actions checked by the policy (spec §6). */
public enum Action {
    VIEW_METADATA,
    VIEW_CONTENT,
    DOWNLOAD,
    UPLOAD,
    EDIT_METADATA,
    CHECK_OUT,
    DELETE,
    RESTORE,
    APPROVE,
    MANAGE_HOLDS,
    MANAGE_RETENTION,
    DISPOSE,
    ADMIN_METADATA,
    ADMIN_ACCESS,
    VIEW_AUDIT,
    /** Importing legacy JDMS documents (ADR-0020); tenant-wide, for the migration client. */
    IMPORT;

    /** Blocked by a legal hold or a declared record (spec §6 step 2); check-out leads to new content. */
    public static final Set<Action> BLOCKED_BY_RECORDS_STATE = EnumSet.of(EDIT_METADATA, CHECK_OUT, DELETE, DISPOSE);
}
