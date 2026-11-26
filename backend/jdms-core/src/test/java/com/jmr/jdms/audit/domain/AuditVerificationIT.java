package com.jmr.jdms.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.TestcontainersConfiguration.OwnerJdbc;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.audit.api.AuditVerificationView;
import com.jmr.jdms.audit.api.AuditVerificationView.Trigger;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B-11 verification job: an intact chain verifies; a changed, re-hashed, removed or cut row is detected and reported
 * with its position. Tampering uses the schema owner's connection, since the application user cannot change audit
 * rows at all (AuditGrantsIT).
 */
@IntegrationTest
class AuditVerificationIT {

    @Autowired MockMvc mvc;
    @Autowired AuditTrail auditTrail;
    @Autowired AuditVerifier verifier;
    @Autowired AuditEventRepository events;
    @Autowired TestTenants testTenants;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired OwnerJdbc ownerJdbc;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder securityAdmin;
    long head;

    @BeforeEach
    void setUp() throws Exception {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001");
        securityAdmin = TestJwts.user(tenant.code()).username("sec").roles("SECURITY_ADMIN").branchScope("ALL");
        // the first request provisions the user (audited); afterwards only the tests' own actions extend the chain
        api.as(securityAdmin, get("/api/v1/me")).andExpect(status().isOk());
        for (int i = 1; i <= 5; i++) {
            record(tenant, i);
        }
        head = jdbc.queryForObject("select event_count from audit_chain_head where tenant_id = ?", Long.class,
                tenant.id());
    }

    @Test
    void anIntactChainVerifies() throws Exception {
        api.as(securityAdmin, get("/api/v1/audit/verification")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIT_VERIFICATION_NOT_FOUND"));

        String id = verify().andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.trigger").value("REQUEST"))
                .andExpect(jsonPath("$.eventsChecked").value(head))
                .andExpect(jsonPath("$.headSeq").value(head))
                .andExpect(jsonPath("$.failure").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        api.as(securityAdmin, get("/api/v1/audit/verification")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DocumentsApi.json(id).get("id").asString()));
        assertThat(outcomesOf(AuditVerifier.AUDIT_VERIFIED)).isEqualTo("SUCCESS");
        // the run's own event extends the chain, and the next run covers it
        verify().andExpect(jsonPath("$.status").value("VALID")).andExpect(jsonPath("$.eventsChecked")
                .value(head + 1));
    }

    @Test
    void aChangedRowIsDetected() throws Exception {
        CreatedTenant other = testTenants.create("001");
        record(other, 1);
        owner().update("update audit_event set actor_id = 'someone-else' where tenant_id = ? and chain_seq = 3",
                tenant.id());

        verify().andExpect(jsonPath("$.status").value("BROKEN"))
                .andExpect(jsonPath("$.failure").value("HASH_MISMATCH"))
                .andExpect(jsonPath("$.brokenSeq").value(3))
                .andExpect(jsonPath("$.brokenEventId").value(eventAt(3).toString()))
                .andExpect(jsonPath("$.eventsChecked").value(2));
        assertThat(outcomesOf(AuditVerifier.AUDIT_VERIFIED)).isEqualTo("ERROR");
        // other tenants' chains are separate
        assertThat(TenantScope.call(other.id(), () -> verifier.verify(Trigger.REQUEST)).failure()).isNull();
    }

    @Test
    void aRowReHashedByTheTampererBreaksTheNextLink() throws Exception {
        UUID target = eventAt(2);
        owner().update("update audit_event set details = '{\"index\": 99}' where id = ?", target);
        String forgedHash = TenantScope.call(tenant.id(), () -> transactions.execute(status ->
                AuditHasher.recompute(events.findById(target).orElseThrow())));
        owner().update("update audit_event set hash = ? where id = ?", forgedHash, target);

        verify().andExpect(jsonPath("$.status").value("BROKEN"))
                .andExpect(jsonPath("$.failure").value("LINK_BROKEN"))
                .andExpect(jsonPath("$.brokenSeq").value(3));
    }

    @Test
    void aRemovedRowIsDetected() throws Exception {
        owner().update("delete from audit_event where tenant_id = ? and chain_seq = 2", tenant.id());

        verify().andExpect(jsonPath("$.failure").value("SEQUENCE_GAP")).andExpect(jsonPath("$.brokenSeq").value(2));
    }

    @Test
    void aCutEndOfTheChainIsDetected() throws Exception {
        owner().update("delete from audit_event where tenant_id = ? and chain_seq = ?", tenant.id(), head);

        verify().andExpect(jsonPath("$.failure").value("SEQUENCE_GAP"))
                .andExpect(jsonPath("$.brokenSeq").value(head));
    }

    @Test
    void aReplacedHeadIsDetected() {
        owner().update("update audit_chain_head set last_hash = ? where tenant_id = ?", "f".repeat(64), tenant.id());

        // run directly: any request could append an event linked to the forged head and fail as LINK_BROKEN instead
        var result = TenantScope.call(tenant.id(), () -> verifier.verify(Trigger.REQUEST));
        assertThat(result.failure()).isEqualTo(AuditVerificationView.Failure.HEAD_MISMATCH);
        assertThat(result.brokenSeq()).isEqualTo(head);
    }

    @Test
    void theDailyJobVerifiesEveryActiveTenant() {
        verifier.daily();

        assertThat(jdbc.queryForObject("select trigger_type || ' ' || status from audit_verification "
                + "where tenant_id = ?", String.class, tenant.id())).isEqualTo("SCHEDULE VALID");
    }

    @Test
    void verificationNeedsViewAudit() throws Exception {
        TestJwts.Builder maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001");
        api.as(maker, post("/api/v1/audit/verification")).andExpect(status().isForbidden());
        api.as(maker, get("/api/v1/audit/verification")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from audit_verification where tenant_id = ?", Long.class,
                tenant.id())).isZero();
    }

    private ResultActions verify() throws Exception {
        return api.as(securityAdmin, post("/api/v1/audit/verification").header("Idempotency-Key",
                DocumentsApi.key())).andExpect(status().isOk());
    }

    private void record(CreatedTenant target, int i) {
        TenantScope.run(target.id(), () -> transactions.executeWithoutResult(status -> auditTrail.record(
                AuditEntry.success("VERIFY_TEST", "TEST", "res-" + i, Map.of("index", i)))));
    }

    private UUID eventAt(long seq) {
        return jdbc.queryForObject("select id from audit_event where tenant_id = ? and chain_seq = ?", UUID.class,
                tenant.id(), seq);
    }

    private String outcomesOf(String action) {
        return jdbc.queryForObject("select string_agg(outcome, ',' order by chain_seq) from audit_event "
                + "where tenant_id = ? and action = ?", String.class, tenant.id(), action);
    }

    private JdbcTemplate owner() {
        return ownerJdbc.jdbc();
    }
}
