package com.jmr.jdms.document.domain;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps each cabinet's stored bytes in step with its versions, in the caller's transaction (B-13). The row is locked
 * until that transaction ends, so two uploads cannot both take the last of a quota.
 */
@Component
class CabinetUsages {

    private final CabinetUsageRepository usages;
    private final TransactionTemplate separately;

    CabinetUsages(CabinetUsageRepository usages, PlatformTransactionManager transactionManager) {
        this.usages = usages;
        this.separately = new TransactionTemplate(transactionManager);
        this.separately.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Adds {@code bytes} if the cabinet stays within {@code quotaBytes}; returns whether it did. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserve(UUID cabinetId, long bytes, long quotaBytes) {
        CabinetUsage usage = locked(cabinetId);
        if (usage.usedBytes() + bytes > quotaBytes) {
            return false;
        }
        usage.add(bytes);
        return true;
    }

    /** Versions that are not new uploads (new versions, drafts, imports) count without a quota check, as before. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(UUID cabinetId, long bytes) {
        locked(cabinetId).add(bytes);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID cabinetId, long bytes) {
        locked(cabinetId).release(bytes);
    }

    private CabinetUsage locked(UUID cabinetId) {
        return usages.lock(cabinetId).orElseGet(() -> {
            // a cabinet created after the migration: its row is made once, outside this transaction
            try {
                separately.executeWithoutResult(status -> usages.saveAndFlush(new CabinetUsage(cabinetId)));
            } catch (DataIntegrityViolationException createdConcurrently) {
                // another upload made it first
            }
            return usages.lock(cabinetId).orElseThrow();
        });
    }
}
