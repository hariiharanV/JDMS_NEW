package com.jmr.jdms.worker.infra;

import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.worker.capture.FieldHint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only access to the captures the worker reads for assisted indexing (ADR-0024; PostgreSQL adapter, the core owns
 * the tables and applies all changes). Every query names the tenant (CLAUDE.md rule 4).
 */
@Component
public class CaptureStore {

    /** What the worker needs to read a capture; {@code content} is null once the core deleted the file. */
    public record CaptureToRead(String tenantCode, UUID documentTypeId, StoredBlob content, long sizeBytes,
            String status) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    CaptureStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CaptureToRead> find(UUID tenantId, UUID captureId) {
        return jdbc.query("""
                select t.code, c.document_type_id, c.blob_key, c.sha256, c.size_bytes, c.encrypted_data_key,
                       c.key_ref, c.status
                  from capture c join tenant t on t.id = c.tenant_id
                 where c.tenant_id = :tenant and c.id = :capture
                """, Map.of("tenant", tenantId, "capture", captureId),
                (rs, row) -> new CaptureToRead(rs.getString("code"), rs.getObject("document_type_id", UUID.class),
                        rs.getString("blob_key") == null ? null : new StoredBlob(rs.getString("blob_key"),
                                rs.getString("sha256"), rs.getLong("size_bytes"), rs.getString("encrypted_data_key"),
                                rs.getString("key_ref")),
                        rs.getLong("size_bytes"), rs.getString("status")))
                .stream().findFirst();
    }

    /** The document type's custom fields with their active list values, in display order. */
    public List<FieldHint> fields(UUID tenantId, UUID documentTypeId) {
        record Row(String key, String label, String type, Integer maxLength, String lovCode) {
        }
        List<Row> rows = jdbc.query("""
                select field_key, label_en, data_type, max_length, lov_code from field_definition
                 where tenant_id = :tenant and document_type_id = :type
                 order by display_row, display_order, field_key
                """, Map.of("tenant", tenantId, "type", documentTypeId),
                (rs, row) -> new Row(rs.getString("field_key"), rs.getString("label_en"), rs.getString("data_type"),
                        rs.getObject("max_length", Integer.class), rs.getString("lov_code")));
        return rows.stream().map(row -> new FieldHint(row.key(), row.label(), row.type(), row.maxLength(),
                row.lovCode() == null ? Map.of() : lovValues(tenantId, row.lovCode()))).toList();
    }

    private Map<String, String> lovValues(UUID tenantId, String lovCode) {
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.query("""
                select v.code, v.label_en from lov_value v join lov l on l.tenant_id = v.tenant_id and l.id = v.lov_id
                 where v.tenant_id = :tenant and l.code = :lov and v.active
                 order by v.display_order, v.code
                """, Map.of("tenant", tenantId, "lov", lovCode),
                rs -> {
                    values.put(rs.getString("code"), rs.getString("label_en"));
                });
        return values;
    }
}
