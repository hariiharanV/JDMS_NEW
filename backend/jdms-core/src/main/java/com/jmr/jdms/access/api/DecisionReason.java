package com.jmr.jdms.access.api;

/** Why a decision came out as it did; the evaluation step of spec §6 that decided. */
public enum DecisionReason {
    ALLOWED_BY_RULE,
    ALLOWED_BY_GRANT,
    DENIED_TENANT_BOUNDARY,
    DENIED_RECORDS_STATE,
    DENIED_EXPLICIT_RULE,
    DENIED_CLEARANCE,
    DENIED_NO_PERMISSION
}
