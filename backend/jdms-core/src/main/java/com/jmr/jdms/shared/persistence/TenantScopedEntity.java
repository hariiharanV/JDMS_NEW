package com.jmr.jdms.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Base class for every table that belongs to a tenant (CLAUDE.md rule 4). Hibernate fills {@code tenant_id} from
 * the current {@code TenantScope} on insert, restricts every query to it, and rejects writes to rows of another
 * tenant. A test asserts that every entity except {@code Tenant} extends this class.
 */
@MappedSuperclass
public abstract class TenantScopedEntity extends AuditedEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    public UUID getTenantId() {
        return tenantId;
    }
}
