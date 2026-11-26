package com.jmr.jdms.workflow.api;

/**
 * Tenant-wide permission checks through the access policy, for workflow decisions that are not about one document
 * (e.g. approving committee changes needs {@code ADMIN_METADATA}). Implemented by the access module, which itself
 * depends on this API, so the dependency points one way.
 */
public interface TenantPermissions {

    /** Whether the caller may perform the tenant-wide action (an {@code access.api.Action} name). */
    boolean allowed(String action);
}
