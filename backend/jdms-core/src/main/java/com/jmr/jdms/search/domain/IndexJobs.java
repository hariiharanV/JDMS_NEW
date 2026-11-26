package com.jmr.jdms.search.domain;

import com.jmr.jdms.contracts.DocumentJobPayload;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.platform.jobs.ClaimedJob;
import com.jmr.jdms.platform.jobs.JobHandler;
import com.jmr.jdms.platform.jobs.JobOutcome;
import com.jmr.jdms.shared.tenant.TenantScope;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code INDEX} (retry of a failed indexing) and {@code REBUILD_INDEX} jobs, run by the core's job runner. */
@Component
class IndexJobs implements JobHandler {

    private final SearchIndexer indexer;
    private final JsonMapper jsonMapper;

    IndexJobs(SearchIndexer indexer, JsonMapper jsonMapper) {
        this.indexer = indexer;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Set<String> types() {
        return Set.of(JobType.INDEX.name(), JobType.REBUILD_INDEX.name());
    }

    @Override
    public JobOutcome handle(ClaimedJob job) {
        DocumentJobPayload payload = jsonMapper.readValue(job.payload(), DocumentJobPayload.class);
        if (!job.tenantId().equals(payload.tenantId())) {
            throw new IllegalStateException("Job tenant and payload tenant differ");
        }
        TenantScope.run(job.tenantId(), () -> {
            if (JobType.REBUILD_INDEX.name().equals(job.type())) {
                indexer.rebuild();
            } else {
                indexer.index(payload.documentId());
            }
        });
        return JobOutcome.done();
    }
}
