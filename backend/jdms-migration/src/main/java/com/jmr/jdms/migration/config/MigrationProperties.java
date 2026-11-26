package com.jmr.jdms.migration.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of a migration run. Secrets come from the environment or files (the legacy secret is a file path, never a
 * value). The legacy database is the tool's only data source ({@code spring.datasource.*}, read-only).
 *
 * @param filesRoot     root of the legacy file tree; every legacy path is resolved inside it
 * @param secretFile    file holding the legacy JDMS secret (CLAUDE.md rule 11)
 * @param core          the JDMS Next core and the migration client's OAuth2 credentials
 * @param reportDir     where the reconciliation report of each run is written
 * @param since         delta mode: only legacy documents created or changed after this instant
 * @param catalog       propose missing document types and folders to the core's administrators instead of importing
 * @param sampleUsers   legacy users whose permissions are compared per document
 * @param maxFileMb     largest legacy file read into memory
 * @param mapping       legacy codes to JDMS Next codes (per bank, confirmed against samples)
 */
@ConfigurationProperties("jdms.migration")
public record MigrationProperties(
        Path filesRoot,
        Path secretFile,
        Core core,
        @DefaultValue("reports") Path reportDir,
        Instant since,
        @DefaultValue("false") boolean catalog,
        @DefaultValue("20") int sampleUsers,
        @DefaultValue("512") int maxFileMb,
        @DefaultValue Mapping mapping) {

    /** @param tokenUri the OAuth2 token endpoint (client credentials) */
    public record Core(URI url, URI tokenUri, String clientId, String clientSecret) {
    }

    /**
     * @param types               {@code <legacy cabinet>/<legacy doc_type_id>} to JDMS type code (default: the legacy
     *                            type ID in upper case)
     * @param cabinets            legacy cabinet ({@code appln_id}) to JDMS cabinet code (default: upper case)
     * @param roles               legacy {@code role_id} to JDMS role code; unmapped roles stop a document
     * @param permissions         legacy {@code doc_permission} letters to JDMS document actions
     * @param decisions           legacy workflow actions to JDMS decisions (SUBMIT, APPROVE, REJECT, RETURN)
     * @param inWorkflow          legacy {@code wf_status} values of documents still in approval (not migrated)
     * @param versionFile         name of older version files in the version folder; {name} and {n} are replaced
     * @param sampleClearance     clearance assumed for sampled legacy users in the permission comparison
     */
    public record Mapping(
            @DefaultValue Map<String, String> types,
            @DefaultValue Map<String, String> cabinets,
            @DefaultValue Map<String, String> roles,
            @DefaultValue Map<String, List<String>> permissions,
            @DefaultValue Map<String, String> decisions,
            @DefaultValue({"NEW", "WORKINPROGRESS"}) List<String> inWorkflow,
            @DefaultValue("{name}.v{n}") String versionFile,
            @DefaultValue("RESTRICTED") String sampleClearance) {
    }
}
