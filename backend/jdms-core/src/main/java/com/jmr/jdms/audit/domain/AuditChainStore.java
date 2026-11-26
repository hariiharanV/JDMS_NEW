package com.jmr.jdms.audit.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * The head of each tenant's hash chain. {@link #lockHead} serialises appends per tenant until the transaction ends.
 * Implemented per database (native SQL lives in adapter classes).
 */
public interface AuditChainStore {

    /** Locks the tenant's chain head, creating it if needed. */
    ChainHead lockHead(UUID tenantId);

    void advance(UUID tenantId, UUID lastEventId, String lastHash);

    /** The committed head without locking, for verification; empty before the tenant's first event. */
    Optional<ChainHead> readHead(UUID tenantId);

    /** The last event's hash and the number of events in the chain (also the last event's {@code chain_seq}). */
    record ChainHead(String lastHash, long eventCount) {
    }
}
