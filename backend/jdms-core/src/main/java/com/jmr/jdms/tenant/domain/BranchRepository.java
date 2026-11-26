package com.jmr.jdms.tenant.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped: every query is restricted to the current {@code TenantScope}. */
public interface BranchRepository extends JpaRepository<Branch, UUID> {

    Optional<Branch> findByCode(String code);

    List<Branch> findByCodeIn(Collection<String> codes);
}
