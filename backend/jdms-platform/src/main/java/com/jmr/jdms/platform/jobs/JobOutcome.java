package com.jmr.jdms.platform.jobs;

import java.util.List;

/** What a successful job leads to: follow-up jobs, enqueued in the transaction that completes the job. */
public record JobOutcome(List<NewJob> followUps) {

    public JobOutcome {
        followUps = List.copyOf(followUps);
    }

    public static JobOutcome done() {
        return new JobOutcome(List.of());
    }

    public static JobOutcome then(NewJob... jobs) {
        return new JobOutcome(List.of(jobs));
    }
}
