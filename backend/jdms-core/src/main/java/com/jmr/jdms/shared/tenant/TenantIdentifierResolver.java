package com.jmr.jdms.shared.tenant;

import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Supplies Hibernate's current tenant for entities with an {@code @TenantId} column: Hibernate adds
 * {@code tenant_id = ?} to every query on them and sets {@code tenant_id} on insert.
 *
 * <p>Fails closed: with no tenant bound it returns {@link #NO_TENANT}, which matches no rows and is rejected on
 * insert by the {@code tenant} foreign key. There is deliberately no "root" tenant that bypasses the filter.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    public static final UUID NO_TENANT = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantScope.currentTenantId().orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
