package com.jmr.jdms.platform.jobs;

import java.util.List;
import java.util.Set;

/**
 * Handles jobs of some types. {@link #handle} must be idempotent: a job can be delivered again after a crash or an
 * expired lease. Any exception other than {@link PermanentJobFailure} is retried with backoff until the job's
 * {@code max_attempts}, after which it is {@code DEAD}.
 */
public interface JobHandler {

    Set<String> types();

    JobOutcome handle(ClaimedJob job) throws Exception;

    /** Jobs to enqueue when the job is given up ({@code FAILED} or {@code DEAD}), e.g. to report the failure. */
    default List<NewJob> onGiveUp(ClaimedJob job, String reason) {
        return List.of();
    }
}
