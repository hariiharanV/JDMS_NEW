package com.jmr.jdms.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** The per-tenant hash chain is written correctly and can be recomputed from what PostgreSQL stores. */
@IntegrationTest
class AuditTrailIT {

    @Autowired AuditTrail auditTrail;
    @Autowired AuditEventRepository events;
    @Autowired TestTenants testTenants;
    @Autowired TransactionTemplate transactions;

    @Test
    void eachTenantHasAnUnbrokenVerifiableChain() {
        CreatedTenant a = testTenants.create();
        CreatedTenant b = testTenants.create();
        // interleave tenants, with nested details and characters that need escaping
        for (int i = 0; i < 3; i++) {
            record(a, i);
            record(b, i);
        }

        for (CreatedTenant tenant : List.of(a, b)) {
            List<AuditEvent> stored = inTenant(tenant, () -> events.findAll());
            // the tenant's chain also holds the baseline access provisioning event of TestTenants
            assertThat(stored.stream().filter(event -> event.getAction().equals("TEST_ACTION"))).hasSize(3);
            assertThat(stored).allSatisfy(event -> {
                assertThat(event.getTenantId()).isEqualTo(tenant.id());
                assertThat(AuditHasher.recompute(event)).as("recomputed hash").isEqualTo(event.getHash());
            });
            assertThat(walkChain(stored)).as("chain from genesis covers every event").hasSize(stored.size());
        }
    }

    @Test
    void tamperingIsDetectable() {
        CreatedTenant tenant = testTenants.create();
        record(tenant, 1);
        AuditEvent stored = inTenant(tenant, () -> events.findByActionOrderByOccurredAt("TEST_ACTION")).getFirst();

        AuditEvent tampered = new AuditEvent(new AuditRecordFields(stored.getId(), stored.getTenantId(),
                stored.getOccurredAt(), stored.getActorType(), "someone-else", stored.getAction(),
                stored.getResourceType(), stored.getResourceId(), stored.getOutcome(), stored.getClientIp(),
                stored.getRequestId(), stored.getDetails()), stored.getChainSeq(), stored.getPrevHash(),
                stored.getHash());

        assertThat(AuditHasher.recompute(tampered)).isNotEqualTo(stored.getHash());
    }

    @Test
    void recordingNeedsATenant() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                auditTrail.record(AuditEntry.success("X", "TEST", "1", Map.of()))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentAppendsInOneTenantKeepTheChainLinear() throws Exception {
        CreatedTenant tenant = testTenants.create();
        List<Thread> threads = IntStream.range(0, 8)
                .mapToObj(i -> Thread.ofVirtual().start(() -> record(tenant, i))).toList();
        for (Thread thread : threads) {
            thread.join();
        }

        List<AuditEvent> stored = inTenant(tenant, () -> events.findAll());
        assertThat(stored.stream().filter(event -> event.getAction().equals("TEST_ACTION"))).hasSize(8);
        List<AuditEvent> chain = walkChain(stored);
        assertThat(chain).hasSize(stored.size());
        assertThat(chain).extracting(AuditEvent::getChainSeq).as("positions follow the chain")
                .containsExactlyElementsOf(LongStream.rangeClosed(1, chain.size()).boxed().toList());
    }

    private void record(CreatedTenant tenant, int i) {
        TenantScope.run(tenant.id(), () -> transactions.executeWithoutResult(status -> auditTrail.record(
                AuditEntry.success("TEST_ACTION", "TEST", "res-" + i,
                        Map.of("index", i, "text", "quote \" and \\ and ünïcödé", "nested",
                                Map.of("b", List.of(1, 2), "a", "x"))))));
    }

    /** Follows prev_hash links from the genesis value; a fork or gap leaves events unvisited. */
    private static List<AuditEvent> walkChain(List<AuditEvent> stored) {
        Map<String, AuditEvent> byPrev = stored.stream()
                .collect(Collectors.toMap(AuditEvent::getPrevHash, Function.identity()));
        List<AuditEvent> chain = new ArrayList<>();
        String next = AuditHasher.GENESIS;
        while (byPrev.containsKey(next)) {
            AuditEvent event = byPrev.get(next);
            chain.add(event);
            next = event.getHash();
        }
        return chain;
    }

    private <T> T inTenant(CreatedTenant tenant, Supplier<T> work) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> work.get()));
    }
}
