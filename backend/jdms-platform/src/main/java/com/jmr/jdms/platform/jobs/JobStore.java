package com.jmr.jdms.platform.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The {@code job} table as a queue (DM {@code job}); implementations are per database. */
public interface JobStore {

    /**
     * Claims up to {@code limit} jobs of the given types that are due, or whose lease expired, skipping rows other
     * runners hold ({@code FOR UPDATE SKIP LOCKED}). Each claim counts as an attempt.
     */
    List<ClaimedJob> claim(Set<String> types, String runner, int limit, Duration lease);

    /**
     * Renews the lease of jobs this runner is still running (heartbeat), so a short lease is safe for long jobs; only
     * rows the runner still holds are touched. Returns how many were renewed.
     */
    int extend(Collection<UUID> ids, String runner, Duration lease);

    void complete(UUID id);

    void retryAt(UUID id, Instant runAfter, String error);

    /** Permanent failure. */
    void fail(UUID id, String error);

    /** Retries exhausted. */
    void dead(UUID id, String error);

    UUID enqueue(NewJob job, int maxAttempts);
}
