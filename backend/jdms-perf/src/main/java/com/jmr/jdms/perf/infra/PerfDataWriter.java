package com.jmr.jdms.perf.infra;

import com.jmr.jdms.perf.generate.SyntheticCorpus.SyntheticDocument;
import com.jmr.jdms.platform.content.StoredBlob;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the synthetic tenant straight into the core's tables (PostgreSQL; bound parameters only). The rows are what
 * the core itself would hold after upload, approval and processing of a plain-text file: an active document, one
 * approved version, its extracted-text rendition, both blobs envelope-encrypted. No audit events: the generator is
 * test-data loading, not user activity, and the tenant holds synthetic data only (ADR-0021).
 */
@Component
public class PerfDataWriter {

    public static final String ACTOR = "perf-generator";
    public static final String SOURCE = "SYNTHETIC";

    /** One generated document with its identifiers and stored blobs. */
    public record Row(UUID documentId, UUID versionId, UUID renditionId, SyntheticDocument document,
            StoredBlob original, StoredBlob text) {
    }

    /** A stored version: its tenant and blob (key, recorded SHA-256, wrapped data key). */
    public record StoredVersion(UUID tenantId, UUID versionId, StoredBlob blob) {
    }

    /** Identifiers of the tenant's catalog, by code. */
    public record Catalog(UUID tenantId, Map<String, UUID> branches, Map<String, UUID> cabinets,
            Map<String, UUID> types) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;
    private final JsonMapper jsonMapper;

    PerfDataWriter(JdbcTemplate jdbc, TransactionTemplate transactions, DataSource dataSource, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.dataSource = dataSource;
        this.jsonMapper = jsonMapper;
    }

    /** Creates the PERF tenant, its branches and catalog if missing ({@code perf/perf-tenant.sql}). */
    public void seed() {
        new ResourceDatabasePopulator(new ClassPathResource("perf/perf-tenant.sql")).execute(dataSource);
    }

    public Catalog catalog(String tenantCode) {
        UUID tenantId = jdbc.queryForObject("select id from tenant where code = ?", UUID.class, tenantCode);
        return new Catalog(tenantId, codes("select code, id from branch where tenant_id = ?", tenantId),
                codes("select code, id from cabinet where tenant_id = ?", tenantId),
                codes("select code, id from document_type where tenant_id = ?", tenantId));
    }

    public long count(UUID tenantId) {
        Long count = jdbc.queryForObject("select count(*) from document where tenant_id = ?", Long.class, tenantId);
        return count == null ? 0 : count;
    }

    /** A random sample of stored versions across all tenants (content checks after a restore). */
    public List<StoredVersion> randomVersions(int sample) {
        return jdbc.query("""
                select tenant_id, id, blob_key, sha256, size_bytes, encrypted_data_key, key_ref
                  from document_version where storage_status = 'STORED' order by random() limit ?
                """, (rs, row) -> new StoredVersion(rs.getObject("tenant_id", UUID.class),
                rs.getObject("id", UUID.class), new StoredBlob(rs.getString("blob_key"), rs.getString("sha256"),
                rs.getLong("size_bytes"), rs.getString("encrypted_data_key"), rs.getString("key_ref"))), sample);
    }

    /** Inserts one batch in one transaction. */
    public void insert(Catalog catalog, List<Row> rows) {
        transactions.executeWithoutResult(status -> {
            jdbc.batchUpdate("""
                    insert into document (id, tenant_id, cabinet_id, document_type_id, title, status, classification,
                                          branch_id, cif, customer_name, account_no, collateral_code, contract_ref,
                                          expiry_date, custom_fields, on_legal_hold, source_system, deleted_at,
                                          status_before_delete, created_at, created_by, updated_at, updated_by,
                                          version_lock)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), false, ?, ?, ?, ?, ?, ?, ?, 0)
                    """, rows, rows.size(), (statement, row) -> {
                SyntheticDocument document = row.document();
                Timestamp created = Timestamp.from(document.createdAt());
                statement.setObject(1, row.documentId());
                statement.setObject(2, catalog.tenantId());
                statement.setObject(3, required(catalog.cabinets(), document.cabinetCode()));
                statement.setObject(4, required(catalog.types(), document.typeCode()));
                statement.setString(5, document.title());
                statement.setString(6, document.deleted() ? "DELETED" : "ACTIVE");
                statement.setString(7, document.classification());
                statement.setObject(8, required(catalog.branches(), document.branchCode()));
                statement.setString(9, document.cif());
                statement.setString(10, document.customerName());
                statement.setString(11, document.accountNo());
                statement.setString(12, document.collateralCode());
                statement.setString(13, document.contractRef());
                if (document.expiryDate() == null) {
                    statement.setNull(14, Types.DATE);
                } else {
                    statement.setDate(14, Date.valueOf(document.expiryDate()));
                }
                statement.setString(15, jsonMapper.writeValueAsString(document.customFields()));
                statement.setString(16, SOURCE);
                statement.setTimestamp(17, document.deleted() ? Timestamp.from(document.createdAt()
                        .plus(30, ChronoUnit.DAYS)) : null);
                statement.setString(18, document.deleted() ? "ACTIVE" : null);
                statement.setTimestamp(19, created);
                statement.setString(20, ACTOR);
                statement.setTimestamp(21, created);
                statement.setString(22, ACTOR);
            });
            jdbc.batchUpdate("""
                    insert into document_version (id, tenant_id, document_id, major, minor, approval_status, blob_key,
                                                  sha256, size_bytes, mime_type, detected_mime_type, original_filename,
                                                  storage_status, processing_status, index_status, encrypted_data_key,
                                                  key_ref, created_at, created_by, updated_at, updated_by,
                                                  version_lock)
                    values (?, ?, ?, 1, 0, 'APPROVED', ?, ?, ?, 'text/plain', 'text/plain', ?, 'STORED', 'DONE',
                            'PENDING', ?, ?, ?, ?, ?, ?, 0)
                    """, rows, rows.size(), (statement, row) -> {
                Timestamp created = Timestamp.from(row.document().createdAt());
                statement.setObject(1, row.versionId());
                statement.setObject(2, catalog.tenantId());
                statement.setObject(3, row.documentId());
                statement.setString(4, row.original().blobKey());
                statement.setString(5, row.original().sha256());
                statement.setLong(6, row.original().sizeBytes());
                statement.setString(7, row.document().fileName());
                statement.setString(8, row.original().wrappedKey());
                statement.setString(9, row.original().keyRef());
                statement.setTimestamp(10, created);
                statement.setString(11, ACTOR);
                statement.setTimestamp(12, created);
                statement.setString(13, ACTOR);
            });
            jdbc.batchUpdate("""
                    insert into rendition (id, tenant_id, version_id, kind, mime_type, blob_key, sha256, size_bytes,
                                           encrypted_data_key, key_ref, ocr, created_at, created_by, updated_at,
                                           updated_by, version_lock)
                    values (?, ?, ?, 'TEXT', 'text/plain; charset=UTF-8', ?, ?, ?, ?, ?, false, ?, ?, ?, ?, 0)
                    """, rows, rows.size(), (statement, row) -> {
                Timestamp now = Timestamp.from(Instant.now());
                statement.setObject(1, row.renditionId());
                statement.setObject(2, catalog.tenantId());
                statement.setObject(3, row.versionId());
                statement.setString(4, row.text().blobKey());
                statement.setString(5, row.text().sha256());
                statement.setLong(6, row.text().sizeBytes());
                statement.setString(7, row.text().wrappedKey());
                statement.setString(8, row.text().keyRef());
                statement.setTimestamp(9, now);
                statement.setString(10, ACTOR);
                statement.setTimestamp(11, now);
                statement.setString(12, ACTOR);
            });
            jdbc.batchUpdate("update document set current_version_id = ? where tenant_id = ? and id = ?", rows,
                    rows.size(), (statement, row) -> {
                        statement.setObject(1, row.versionId());
                        statement.setObject(2, catalog.tenantId());
                        statement.setObject(3, row.documentId());
                    });
        });
    }

    private Map<String, UUID> codes(String sql, UUID tenantId) {
        Map<String, UUID> codes = new HashMap<>();
        jdbc.query(sql, rs -> {
            codes.put(rs.getString("code"), rs.getObject("id", UUID.class));
        }, tenantId);
        return codes;
    }

    private static UUID required(Map<String, UUID> ids, String code) {
        UUID id = ids.get(code);
        if (id == null) {
            throw new IllegalStateException("The PERF catalog has no entry " + code + " (run the seed command)");
        }
        return id;
    }
}
