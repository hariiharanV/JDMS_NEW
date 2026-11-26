package com.jmr.jdms.shared.idempotency;

import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.TenantDirectory;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Removes idempotency records older than 24 hours, tenant by tenant. */
@Component
class IdempotencyCleanup {

    private final TenantDirectory tenants;
    private final IdempotencyRecordRepository records;
    private final TransactionTemplate transactions;

    IdempotencyCleanup(TenantDirectory tenants, IdempotencyRecordRepository records, TransactionTemplate transactions) {
        this.tenants = tenants;
        this.records = records;
        this.transactions = transactions;
    }

    @Scheduled(cron = "${jdms.idempotency.cleanup-cron:0 15 * * * *}")
    void removeExpired() {
        tenants.findAllActive().forEach(tenant -> TenantScope.run(tenant.id(),
                () -> transactions.executeWithoutResult(status -> records.deleteExpired(Instant.now()))));
    }
}
