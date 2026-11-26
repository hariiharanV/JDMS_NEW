package com.jmr.jdms.audit.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditOutcome;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.audit.api.AuditVerificationView;
import com.jmr.jdms.audit.api.AuditVerificationView.Failure;
import com.jmr.jdms.audit.api.AuditVerificationView.Trigger;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.TenantDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies each tenant's hash chain (spec §4.11): walks the events in chain order up to the head committed when the
 * run starts, recomputes every hash and checks every link, then compares the last event with the head. Events
 * appended during the run are left for the next one. Runs daily for every active tenant and on request.
 */
@Service
public class AuditVerifier {

    public static final String AUDIT_VERIFIED = "AUDIT_VERIFIED";

    private static final Logger log = LoggerFactory.getLogger(AuditVerifier.class);
    private static final int PAGE = 500;

    private final AuditEventRepository events;
    private final AuditVerificationRepository verifications;
    private final AuditChainStore chain;
    private final AuditTrail auditTrail;
    private final TenantDirectory tenants;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate writes;
    private final Clock clock;

    @Autowired
    AuditVerifier(AuditEventRepository events, AuditVerificationRepository verifications, AuditChainStore chain,
            AuditTrail auditTrail, TenantDirectory tenants, PlatformTransactionManager transactionManager) {
        this(events, verifications, chain, auditTrail, tenants, transactionManager, Clock.systemUTC());
    }

    AuditVerifier(AuditEventRepository events, AuditVerificationRepository verifications, AuditChainStore chain,
            AuditTrail auditTrail, TenantDirectory tenants, PlatformTransactionManager transactionManager,
            Clock clock) {
        this.events = events;
        this.verifications = verifications;
        this.chain = chain;
        this.auditTrail = auditTrail;
        this.tenants = tenants;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.writes = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "${jdms.audit.verification.cron:0 0 4 * * *}")
    void daily() {
        for (var tenant : tenants.findAllActive()) {
            try {
                TenantScope.run(tenant.id(), () -> verify(Trigger.SCHEDULE));
            } catch (RuntimeException e) {
                log.warn("Audit verification failed to run for tenant {}: {}", tenant.code(), e.toString());
            }
        }
    }

    /** Verifies the current tenant's chain, stores the result and records an {@code AUDIT_VERIFIED} event. */
    public AuditVerificationView verify(Trigger trigger) {
        UUID tenantId = TenantScope.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("Verification needs a tenant in TenantScope"));
        Instant startedAt = now();
        AuditChainStore.ChainHead head = chain.readHead(tenantId)
                .orElse(new AuditChainStore.ChainHead(AuditHasher.GENESIS, 0));

        Walk walk = new Walk();
        while (walk.failure == null && walk.lastSeq < head.eventCount()) {
            List<AuditEvent> page = readOnly.execute(status -> events.findByChainSeqBetweenOrderByChainSeq(
                    walk.lastSeq + 1, head.eventCount(), Limit.of(PAGE)));
            if (page.isEmpty()) {
                walk.fail(walk.lastSeq + 1, null, Failure.SEQUENCE_GAP);
                break;
            }
            page.forEach(walk::check);
        }
        if (walk.failure == null && !walk.prevHash.equals(head.lastHash())) {
            walk.fail(walk.lastSeq, walk.lastEventId, Failure.HEAD_MISMATCH);
        }

        AuditVerification result = new AuditVerification(Ids.newId(), startedAt, now(), trigger, walk.checked,
                head.eventCount(), head.lastHash(), walk.brokenSeq, walk.brokenEventId, walk.failure);
        if (walk.failure != null) {
            log.error("Audit hash chain of tenant {} is broken at position {}: {}", tenantId, walk.brokenSeq,
                    walk.failure);
        }
        return writes.execute(status -> {
            verifications.save(result);
            Map<String, Object> details = new HashMap<>();
            details.put("status", result.view().status().name());
            details.put("eventsChecked", walk.checked);
            details.put("trigger", trigger.name());
            if (walk.failure != null) {
                details.put("failure", walk.failure.name());
                details.put("brokenSeq", walk.brokenSeq);
            }
            auditTrail.record(new AuditEntry(AUDIT_VERIFIED, "AUDIT_VERIFICATION", result.getId().toString(),
                    walk.failure == null ? AuditOutcome.SUCCESS : AuditOutcome.ERROR, details, null, null));
            return result.view();
        });
    }

    /** The latest stored result for the current tenant. */
    public Optional<AuditVerificationView> latest() {
        return readOnly.execute(status -> verifications.findFirstByOrderByFinishedAtDesc()
                .map(AuditVerification::view));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** State of one pass over the chain; stops at the first failure. */
    private static final class Walk {

        long lastSeq;
        UUID lastEventId;
        String prevHash = AuditHasher.GENESIS;
        long checked;
        Long brokenSeq;
        UUID brokenEventId;
        Failure failure;

        void check(AuditEvent event) {
            if (failure != null) {
                return;
            }
            if (event.getChainSeq() != lastSeq + 1) {
                fail(lastSeq + 1, event.getId(), Failure.SEQUENCE_GAP);
            } else if (!event.getPrevHash().equals(prevHash)) {
                fail(event.getChainSeq(), event.getId(), Failure.LINK_BROKEN);
            } else if (!AuditHasher.recompute(event).equals(event.getHash())) {
                fail(event.getChainSeq(), event.getId(), Failure.HASH_MISMATCH);
            } else {
                checked++;
                lastSeq = event.getChainSeq();
                lastEventId = event.getId();
                prevHash = event.getHash();
            }
        }

        void fail(long seq, UUID eventId, Failure reason) {
            brokenSeq = seq;
            brokenEventId = eventId;
            failure = reason;
        }
    }
}
