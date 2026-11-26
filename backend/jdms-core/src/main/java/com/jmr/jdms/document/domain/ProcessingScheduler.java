package com.jmr.jdms.document.domain;

import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.VersionCreated;
import com.jmr.jdms.contracts.VersionJobPayload;
import com.jmr.jdms.shared.job.JobQueue;
import com.jmr.jdms.shared.tenant.TenantScope;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts processing of every new version: after the upload commits, the outbox (Spring Modulith event publication
 * registry) delivers {@link VersionCreated} and a SCAN job is queued for the worker (B-06). If this fails, the
 * publication stays incomplete and is redelivered ({@code EventRedelivery}); a second SCAN of a processed version is
 * harmless. The tenant is bound before the transaction starts, as Hibernate fixes a session's tenant when it opens.
 */
@Component
class ProcessingScheduler {

    private final JobQueue jobs;
    private final TransactionTemplate transactions;

    ProcessingScheduler(JobQueue jobs, TransactionTemplate transactions) {
        this.jobs = jobs;
        this.transactions = transactions;
    }

    @Async
    @TransactionalEventListener
    void on(VersionCreated event) {
        TenantScope.run(event.tenantId(), () -> transactions.executeWithoutResult(status -> jobs.enqueue(JobType.SCAN,
                new VersionJobPayload(event.tenantId(), event.documentId(), event.versionId()))));
    }
}
