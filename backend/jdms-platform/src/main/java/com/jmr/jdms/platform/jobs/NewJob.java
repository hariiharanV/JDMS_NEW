package com.jmr.jdms.platform.jobs;

import java.util.UUID;

/** A job to enqueue; {@code payload} is JSON with IDs only (never content or secrets). */
public record NewJob(UUID tenantId, String type, String payload) {
}
