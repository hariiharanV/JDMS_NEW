package com.jmr.jdms.worker.infra;

import com.jmr.jdms.platform.content.StoredBlob;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only access to the versions the worker processes (PostgreSQL adapter; the core owns these tables and applies
 * all changes). Every query names the tenant (CLAUDE.md rule 4).
 */
@Component
public class VersionStore {

    /** What the worker needs to know about a version; never its metadata. */
    public record VersionToProcess(String tenantCode, StoredBlob content, long sizeBytes, String processingStatus) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    VersionStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VersionToProcess> find(UUID tenantId, UUID documentId, UUID versionId) {
        return jdbc.query("""
                select t.code, v.blob_key, v.sha256, v.size_bytes, v.encrypted_data_key, v.key_ref, v.processing_status
                  from document_version v join tenant t on t.id = v.tenant_id
                 where v.tenant_id = :tenant and v.document_id = :document and v.id = :version
                """, Map.of("tenant", tenantId, "document", documentId, "version", versionId),
                (rs, row) -> new VersionToProcess(rs.getString("code"), new StoredBlob(rs.getString("blob_key"),
                        rs.getString("sha256"), rs.getLong("size_bytes"), rs.getString("encrypted_data_key"),
                        rs.getString("key_ref")), rs.getLong("size_bytes"), rs.getString("processing_status")))
                .stream().findFirst();
    }

    /** Whether the core already recorded a rendition of this kind (skips repeating work on duplicate delivery). */
    public boolean hasRendition(UUID tenantId, UUID versionId, String kind) {
        Integer count = jdbc.queryForObject("""
                select count(*) from rendition where tenant_id = :tenant and version_id = :version and kind = :kind
                """, Map.of("tenant", tenantId, "version", versionId, "kind", kind), Integer.class);
        return count != null && count > 0;
    }
}
