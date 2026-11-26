package com.jmr.jdms.document.infra;

import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.ProcessingResult;
import com.jmr.jdms.document.domain.ProcessingResultService;
import com.jmr.jdms.platform.jobs.ClaimedJob;
import com.jmr.jdms.platform.jobs.JobHandler;
import com.jmr.jdms.platform.jobs.JobOutcome;
import com.jmr.jdms.shared.tenant.TenantScope;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Consumes the worker's {@code PROCESSING_RESULT} jobs in the core (ADR-0014). */
@Component
class ProcessingResultJobs implements JobHandler {

    private final ProcessingResultService results;
    private final JsonMapper jsonMapper;

    ProcessingResultJobs(ProcessingResultService results, JsonMapper jsonMapper) {
        this.results = results;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Set<String> types() {
        return Set.of(JobType.PROCESSING_RESULT.name());
    }

    @Override
    public JobOutcome handle(ClaimedJob job) {
        ProcessingResult result = jsonMapper.readValue(job.payload(), ProcessingResult.class);
        if (!job.tenantId().equals(result.tenantId())) {
            throw new IllegalStateException("Job tenant and payload tenant differ");
        }
        // the tenant is bound before the transaction starts (Hibernate fixes a session's tenant when it opens)
        TenantScope.run(job.tenantId(), () -> results.apply(result));
        return JobOutcome.done();
    }
}
