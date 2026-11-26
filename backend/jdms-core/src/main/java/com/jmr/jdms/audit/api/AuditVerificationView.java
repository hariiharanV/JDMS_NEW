package com.jmr.jdms.audit.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The result of verifying a tenant's hash chain up to its head at the start of the run. A broken chain names the
 * first position that failed and why.
 */
public record AuditVerificationView(
        UUID id,
        Instant startedAt,
        Instant finishedAt,
        Trigger trigger,
        Status status,
        long eventsChecked,
        long headSeq,
        String headHash,
        Long brokenSeq,
        UUID brokenEventId,
        Failure failure) {

    public enum Trigger {
        SCHEDULE,
        REQUEST
    }

    public enum Status {
        VALID,
        BROKEN
    }

    public enum Failure {
        /** The stored fields no longer produce the stored hash: the row was changed. */
        HASH_MISMATCH,
        /** {@code prev_hash} is not the previous event's hash: a row was removed, inserted or reordered. */
        LINK_BROKEN,
        /** A position is missing or repeated: rows were removed. */
        SEQUENCE_GAP,
        /** The last event does not match the chain head: the end of the chain was cut or replaced. */
        HEAD_MISMATCH
    }
}
