package com.jmr.jdms.platform.jobs;

import java.util.UUID;

/** A job claimed by a runner: its lease is held until it completes, fails or the lease expires. */
public record ClaimedJob(UUID id, UUID tenantId, String type, String payload, int attempts, int maxAttempts) {
}
