package com.jmr.jdms.platform.content;

import java.util.UUID;

/** Wraps and unwraps per-object data keys with the tenant's key (envelope encryption, D-12). */
public interface KeyService {

    /** The wrapped key, and a reference to the tenant key version that wrapped it (stored as {@code key_ref}). */
    record WrappedKey(String keyRef, String wrapped) {
    }

    WrappedKey wrap(UUID tenantId, byte[] dataKey);

    byte[] unwrap(UUID tenantId, String keyRef, String wrapped);
}
