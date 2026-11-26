package com.jmr.jdms.tenant.api;

import java.util.UUID;

/** A branch of the current tenant. */
public record BranchInfo(UUID id, String code, String name) {
}
