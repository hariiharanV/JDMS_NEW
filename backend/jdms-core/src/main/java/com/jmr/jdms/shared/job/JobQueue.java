package com.jmr.jdms.shared.job;

import com.jmr.jdms.contracts.JobType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Enqueues background jobs for the current tenant. Payloads are contract records: IDs and tenant only. */
@Component
public class JobQueue {

    private final JobRepository jobs;
    private final JsonMapper jsonMapper;

    JobQueue(JobRepository jobs, JsonMapper jsonMapper) {
        this.jobs = jobs;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Job enqueue(JobType type, Object payload) {
        return jobs.save(new Job(type, jsonMapper.writeValueAsString(payload),
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }
}
