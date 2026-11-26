package com.jmr.jdms.audit.api;

import java.util.List;

/**
 * Reads the current tenant's audit trail. Callers authorize first ({@code VIEW_AUDIT}); every read is itself recorded
 * as an {@code AUDIT_QUERIED} event (spec §4.11).
 */
public interface AuditQueries {

    /** Events about one resource, oldest first, at most {@code limit}. */
    List<AuditEventView> forResource(String resourceType, String resourceId, int limit);
}
