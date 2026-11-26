package com.jmr.jdms.document.infra;

import com.jmr.jdms.contracts.CaptureResult;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.document.domain.CaptureService;
import com.jmr.jdms.platform.jobs.ClaimedJob;
import com.jmr.jdms.platform.jobs.JobHandler;
import com.jmr.jdms.platform.jobs.JobOutcome;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.TenantDirectory;
import java.time.Instant;
import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assisted indexing in the background (ADR-0024): applies the worker's {@code CAPTURE_RESULT} jobs and removes
 * expired captures, tenant by tenant.
 */
@Component
class CaptureJobs implements JobHandler {

    private final CaptureService captures;
    private final TenantDirectory tenants;
    private final JsonMapper jsonMapper;

    CaptureJobs(CaptureService captures, TenantDirectory tenants, JsonMapper jsonMapper) {
        this.captures = captures;
        this.tenants = tenants;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Set<String> types() {
        return Set.of(JobType.CAPTURE_RESULT.name());
    }

    @Override
    public JobOutcome handle(ClaimedJob job) {
        CaptureResult result = jsonMapper.readValue(job.payload(), CaptureResult.class);
        if (!job.tenantId().equals(result.tenantId())) {
            throw new IllegalStateException("Job tenant and payload tenant differ");
        }
        // the tenant is bound before the transaction starts (Hibernate fixes a session's tenant when it opens)
        TenantScope.run(job.tenantId(), () -> captures.apply(result));
        return JobOutcome.done();
    }

    @Scheduled(cron = "${jdms.capture.expiry-cron:0 */10 * * * *}")
    void removeExpired() {
        Instant now = Instant.now();
        tenants.findAllActive().forEach(tenant -> TenantScope.run(tenant.id(), () -> {
            while (captures.expire(now) > 0) {
                // next batch
            }
        }));
    }
}
