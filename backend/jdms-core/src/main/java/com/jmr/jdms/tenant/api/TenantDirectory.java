package com.jmr.jdms.tenant.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Looks up tenants. Not tenant-scoped: used to establish the tenant of a request. */
public interface TenantDirectory {

    /** The tenant with this code if it exists and is {@code ACTIVE}; suspended and unknown tenants are empty. */
    Optional<TenantInfo> findActiveByCode(String code);

    /** The tenant with this ID (any status), e.g. for background work on its data. */
    Optional<TenantInfo> findById(UUID id);

    /** All active tenants, for background work that runs per tenant. */
    List<TenantInfo> findAllActive();
}
