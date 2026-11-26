package com.jmr.jdms.search.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.DocumentJobPayload;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.job.Job;
import com.jmr.jdms.shared.job.JobQueue;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operations on the tenant's search index. */
@Service
public class SearchAdministration {

    private final JobQueue jobs;
    private final AuditTrail auditTrail;

    SearchAdministration(JobQueue jobs, AuditTrail auditTrail) {
        this.jobs = jobs;
        this.auditTrail = auditTrail;
    }

    /** Queues a full rebuild of the caller's tenant index from the database; returns the job ID. */
    @Transactional
    public UUID requestRebuild() {
        UUID tenantId = RequestContext.require().tenantId();
        Job job = jobs.enqueue(JobType.REBUILD_INDEX, new DocumentJobPayload(tenantId, null));
        auditTrail.record(AuditEntry.success("SEARCH_REBUILD_REQUESTED", "JOB", job.getId(), Map.of()));
        return job.getId();
    }
}
