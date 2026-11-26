package com.jmr.jdms.audit.infra;

import com.jmr.jdms.audit.domain.AuditChainStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL adapter for the audit chain head. Native SQL with bound parameters only; the tenant is always an
 * explicit parameter because Hibernate's tenant filter does not apply to native SQL.
 */
@Component
class PostgresAuditChainStore implements AuditChainStore {

    private static final String GENESIS = "0".repeat(64);
    private static final RowMapper<ChainHead> HEAD = (row, number) -> new ChainHead(row.getString("last_hash"),
            row.getLong("event_count"));

    private final JdbcTemplate jdbc;

    PostgresAuditChainStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ChainHead lockHead(UUID tenantId) {
        jdbc.update("insert into audit_chain_head (tenant_id, last_hash, event_count) values (?, ?, 0) "
                + "on conflict (tenant_id) do nothing", tenantId, GENESIS);
        return jdbc.queryForObject("select last_hash, event_count from audit_chain_head where tenant_id = ? "
                + "for update", HEAD, tenantId);
    }

    @Override
    public void advance(UUID tenantId, UUID lastEventId, String lastHash) {
        jdbc.update("update audit_chain_head set last_event_id = ?, last_hash = ?, event_count = event_count + 1 "
                + "where tenant_id = ?", lastEventId, lastHash, tenantId);
    }

    @Override
    public Optional<ChainHead> readHead(UUID tenantId) {
        return jdbc.query("select last_hash, event_count from audit_chain_head where tenant_id = ?", HEAD, tenantId)
                .stream().findFirst();
    }
}
