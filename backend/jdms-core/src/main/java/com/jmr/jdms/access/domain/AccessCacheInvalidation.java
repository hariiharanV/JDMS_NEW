package com.jmr.jdms.access.domain;

import com.jmr.jdms.contracts.AccessRulesChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Evicts a tenant's cached rules once a rule change has committed (event-driven part of revocation). */
@Component
class AccessCacheInvalidation {

    private final RuleCache cache;

    AccessCacheInvalidation(RuleCache cache) {
        this.cache = cache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(AccessRulesChanged event) {
        cache.evict(event.tenantId());
    }
}
