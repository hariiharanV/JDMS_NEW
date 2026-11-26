package com.jmr.jdms.shared.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant that data access is scoped to on the current thread. Every tenant-scoped repository query and insert
 * uses it (see {@link TenantIdentifierResolver}); with no tenant bound, tenant-scoped data is invisible and cannot be
 * written.
 *
 * <p>For HTTP requests the tenant is bound from the authenticated {@code RequestContext}. Background work (jobs,
 * event listeners) binds the tenant carried by its payload with {@link #call} or {@link #run}.
 */
public final class TenantScope {

    private static final ScopedValue<UUID> CURRENT = ScopedValue.newInstance();

    private TenantScope() {
    }

    public static Optional<UUID> currentTenantId() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    public static <T, X extends Throwable> T call(UUID tenantId, ScopedValue.CallableOp<T, X> operation) throws X {
        return ScopedValue.where(CURRENT, Objects.requireNonNull(tenantId, "tenantId")).call(operation);
    }

    /** A binding of the tenant, for callers that bind several scoped values together. */
    public static ScopedValue.Carrier bind(UUID tenantId) {
        return ScopedValue.where(CURRENT, Objects.requireNonNull(tenantId, "tenantId"));
    }

    public static void run(UUID tenantId, Runnable operation) {
        ScopedValue.where(CURRENT, Objects.requireNonNull(tenantId, "tenantId")).run(operation);
    }
}
