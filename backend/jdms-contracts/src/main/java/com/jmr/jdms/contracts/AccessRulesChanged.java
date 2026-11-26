package com.jmr.jdms.contracts;

import java.util.UUID;

/** Access rules or document grants of a tenant changed: caches are evicted and search ACLs re-indexed. */
public record AccessRulesChanged(UUID tenantId) {
}
