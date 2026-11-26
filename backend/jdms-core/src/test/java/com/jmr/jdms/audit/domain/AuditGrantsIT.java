package com.jmr.jdms.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.audit.api.AuditVerificationView;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B-11: the application's database user can only insert and read audit rows (DM audit, ADR-0018). The application's
 * own connection is used, exactly as the running core would use it.
 */
@IntegrationTest
class AuditGrantsIT {

    /** PostgreSQL's insufficient_privilege. */
    private static final String PERMISSION_DENIED = "42501";

    @Autowired JdbcTemplate jdbc;
    @Autowired AuditTrail auditTrail;
    @Autowired AuditVerifier verifier;
    @Autowired TestTenants testTenants;
    @Autowired TransactionTemplate transactions;

    CreatedTenant tenant;
    UUID eventId;
    UUID verificationId;

    @BeforeEach
    void setUp() {
        tenant = testTenants.create();
        TenantScope.run(tenant.id(), () -> {
            transactions.executeWithoutResult(status -> auditTrail.record(
                    AuditEntry.success("GRANT_TEST", "TEST", "1", Map.of())));
            verificationId = verifier.verify(AuditVerificationView.Trigger.REQUEST).id();
        });
        eventId = jdbc.queryForObject("select id from audit_event where tenant_id = ? and action = 'GRANT_TEST'",
                UUID.class, tenant.id());
    }

    @Test
    void theApplicationIsNeitherSuperuserNorOwner() {
        assertThat(jdbc.queryForObject("select rolsuper from pg_roles where rolname = current_user", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("select tableowner from pg_tables where tablename = 'audit_event'",
                String.class)).isNotEqualTo(jdbc.queryForObject("select current_user", String.class));
    }

    @Test
    void auditEventsCannotBeChangedOrRemoved() {
        assertDenied(() -> jdbc.update("update audit_event set actor_id = ? where id = ?", "someone-else", eventId));
        assertDenied(() -> jdbc.update("delete from audit_event where id = ?", eventId));
        assertDenied(() -> jdbc.update("truncate audit_event"));
        assertDenied(() -> jdbc.update("alter table audit_event drop constraint uq_audit_event_seq"));
        assertDenied(() -> jdbc.update("drop table audit_event"));

        assertThat(jdbc.queryForObject("select actor_id from audit_event where id = ?", String.class, eventId))
                .isEqualTo("system");
        assertThat(privileges("audit_event")).isEqualTo("INSERT SELECT");
    }

    @Test
    void verificationResultsCannotBeChangedOrRemoved() {
        assertDenied(() -> jdbc.update("update audit_verification set status = 'VALID' where id = ?",
                verificationId));
        assertDenied(() -> jdbc.update("delete from audit_verification where id = ?", verificationId));
        assertThat(privileges("audit_verification")).isEqualTo("INSERT SELECT");
    }

    @Test
    void theMigrationHistoryIsOutOfReach() {
        assertDenied(() -> jdbc.queryForObject("select count(*) from flyway_schema_history", Long.class));
        assertDenied(() -> jdbc.update("delete from flyway_schema_history"));
    }

    @Test
    void otherTablesKeepFullDataAccess() {
        assertThat(privileges("document")).isEqualTo("DELETE INSERT SELECT UPDATE");
        assertThat(privileges("audit_chain_head")).isEqualTo("DELETE INSERT SELECT UPDATE");
    }

    private String privileges(String table) {
        return jdbc.queryForObject("select string_agg(p, ' ' order by p) from unnest(array['SELECT', 'INSERT', "
                + "'UPDATE', 'DELETE', 'TRUNCATE']) p where has_table_privilege(current_user, ?, p)", String.class,
                table);
    }

    private static void assertDenied(Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(e)).getSQLState())
                        .isEqualTo(PERMISSION_DENIED));
    }
}
