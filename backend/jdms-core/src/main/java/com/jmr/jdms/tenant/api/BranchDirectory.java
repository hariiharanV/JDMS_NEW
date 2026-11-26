package com.jmr.jdms.tenant.api;

import java.util.Collection;
import java.util.List;

/** Looks up branches of the current tenant ({@code TenantScope}). */
public interface BranchDirectory {

    /** The branches with these codes; unknown codes are left out. */
    List<BranchInfo> findByCodes(Collection<String> codes);

    /** The branches with these IDs; unknown IDs and other tenants' branches are left out. */
    List<BranchInfo> findByIds(Collection<java.util.UUID> ids);

    /** All branches of the current tenant, by code. */
    List<BranchInfo> findAll();
}
