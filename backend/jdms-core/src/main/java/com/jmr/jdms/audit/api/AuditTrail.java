package com.jmr.jdms.audit.api;

/**
 * Records audit events (CLAUDE.md rule 10). Joins the caller's transaction, so an event is committed exactly when the
 * state change it describes is committed. Requires a tenant in {@code TenantScope}.
 */
public interface AuditTrail {

    void record(AuditEntry entry);
}
