package com.jmr.jdms.migration.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reconciliation report of one run (B-12, ADR-0020): every legacy document ends in exactly one outcome; counts
 * per cabinet and type, checksums, field differences, version and history counts, the permission comparison and the
 * exceptions are written as {@code reconciliation.json} and a readable {@code reconciliation.md}.
 */
public final class Reconciliation {

    /** What happened to one legacy document. Exceptions are every outcome other than the first two. */
    public enum Outcome {
        MIGRATED,
        ALREADY_MIGRATED,
        CHANGED_AFTER_MIGRATION,
        MISSING_FILE,
        UNDECRYPTABLE,
        CORRUPT,
        IN_WORKFLOW,
        TYPE_NOT_MIGRATED,
        UNMAPPED,
        INVALID_METADATA,
        CHECKSUM_MISMATCH,
        FAILED;

        public boolean isException() {
            return this != MIGRATED && this != ALREADY_MIGRATED;
        }
    }

    public record Checksum(String version, String legacySha256, String jdmsSha256, boolean match) {
    }

    public record Difference(String field, String legacy, String jdms) {
    }

    public record DocumentResult(String legacyDocumentId, String cabinet, String type, Outcome outcome,
            UUID documentId, int legacyVersions, int jdmsVersions, int legacyHistory, int jdmsHistory,
            List<Checksum> checksums, List<Difference> differences, List<String> warnings, String reason) {
    }

    public record PermissionCheck(String legacyUser, String legacyDocumentId, List<String> legacyActions,
            List<String> jdmsActions, List<String> missing, List<String> extra) {
    }

    public record Counts(int legacy, int migrated, int alreadyMigrated, int exceptions) {
    }

    private final String runId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();
    private final String mode;
    private final Instant since;
    private final List<DocumentResult> documents = new ArrayList<>();
    private final List<PermissionCheck> permissions = new ArrayList<>();
    private final List<String> catalog = new ArrayList<>();
    private Instant finishedAt;

    public Reconciliation(String mode, Instant since) {
        this.mode = mode;
        this.since = since;
    }

    public synchronized void add(DocumentResult result) {
        documents.add(result);
    }

    public synchronized void add(PermissionCheck check) {
        permissions.add(check);
    }

    /** A catalog proposal or problem (types, folders), for the catalog step. */
    public synchronized void catalog(String line) {
        catalog.add(line);
    }

    public List<DocumentResult> documents() {
        return List.copyOf(documents);
    }

    public List<PermissionCheck> permissions() {
        return List.copyOf(permissions);
    }

    public List<String> catalogLines() {
        return List.copyOf(catalog);
    }

    /** Counts per {@code cabinet/type}. */
    public Map<String, Counts> counts() {
        Map<String, Counts> counts = new TreeMap<>();
        for (DocumentResult result : documents) {
            String key = result.cabinet() + "/" + result.type();
            Counts current = counts.getOrDefault(key, new Counts(0, 0, 0, 0));
            counts.put(key, new Counts(current.legacy() + 1,
                    current.migrated() + (result.outcome() == Outcome.MIGRATED ? 1 : 0),
                    current.alreadyMigrated() + (result.outcome() == Outcome.ALREADY_MIGRATED ? 1 : 0),
                    current.exceptions() + (result.outcome().isException() ? 1 : 0)));
        }
        return counts;
    }

    public List<DocumentResult> exceptions() {
        return documents.stream().filter(result -> result.outcome().isException()).toList();
    }

    /** Writes both report files; returns the JSON file. */
    public Path write(Path directory) {
        finishedAt = Instant.now();
        try {
            Path runDirectory = directory.resolve("run-" + startedAt.toString().replace(':', '-'));
            Files.createDirectories(runDirectory);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("runId", runId);
            body.put("mode", mode);
            body.put("since", since == null ? null : since.toString());
            body.put("startedAt", startedAt.toString());
            body.put("finishedAt", finishedAt.toString());
            body.put("counts", counts());
            body.put("documents", documents);
            body.put("exceptions", exceptions().stream().map(DocumentResult::legacyDocumentId).toList());
            body.put("permissions", permissions);
            body.put("catalog", catalog);
            Path jsonFile = runDirectory.resolve("reconciliation.json");
            Files.writeString(jsonFile, JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
                    .writeValueAsString(body), StandardCharsets.UTF_8);
            Files.writeString(runDirectory.resolve("reconciliation.md"), markdown(), StandardCharsets.UTF_8);
            return jsonFile;
        } catch (IOException e) {
            throw new UncheckedIOException("The reconciliation report could not be written", e);
        }
    }

    private String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Migration reconciliation\n\n");
        md.append("Run ").append(runId).append(", mode ").append(mode);
        if (since != null) {
            md.append(" since ").append(since);
        }
        md.append(", ").append(startedAt).append(" to ").append(finishedAt).append(".\n\n");
        if ("CATALOG".equals(mode)) {
            md.append("## Catalog\n\n");
            catalog.forEach(line -> md.append("- ").append(cell(line)).append('\n'));
            return md.toString();
        }
        md.append("## Counts per cabinet and type\n\n| Cabinet/type | Legacy | Migrated | Already migrated | Exceptions |\n")
                .append("| --- | ---: | ---: | ---: | ---: |\n");
        counts().forEach((key, count) -> md.append("| ").append(key).append(" | ").append(count.legacy())
                .append(" | ").append(count.migrated()).append(" | ").append(count.alreadyMigrated()).append(" | ")
                .append(count.exceptions()).append(" |\n"));
        long checksums = documents.stream().mapToLong(result -> result.checksums().size()).sum();
        long matching = documents.stream().flatMap(result -> result.checksums().stream()).filter(Checksum::match)
                .count();
        long differences = documents.stream().mapToLong(result -> result.differences().size()).sum();
        md.append("\n## Integrity\n\n- Files compared by SHA-256: ").append(checksums).append(", matching: ")
                .append(matching).append("\n- Metadata differences: ").append(differences).append("\n");
        md.append("\n## Exceptions\n\n");
        if (exceptions().isEmpty()) {
            md.append("None.\n");
        } else {
            md.append("| Legacy document | Cabinet/type | Outcome | Reason |\n| --- | --- | --- | --- |\n");
            exceptions().forEach(result -> md.append("| ").append(result.legacyDocumentId()).append(" | ")
                    .append(result.cabinet()).append('/').append(result.type()).append(" | ")
                    .append(result.outcome()).append(" | ").append(cell(result.reason()))
                    .append(" |\n"));
        }
        md.append("\n## Permissions (sampled users)\n\n");
        long gaps = permissions.stream().filter(check -> !check.missing().isEmpty()).count();
        md.append("Checks: ").append(permissions.size()).append(", with actions missing in JDMS Next: ").append(gaps)
                .append(", with extra actions (review): ")
                .append(permissions.stream().filter(check -> !check.extra().isEmpty()).count()).append("\n");
        if (!catalog.isEmpty()) {
            md.append("\n## Catalog\n\n");
            catalog.forEach(line -> md.append("- ").append(cell(line)).append('\n'));
        }
        return md.toString();
    }

    /** Text from legacy data as one Markdown table cell: no pipes or line breaks. */
    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replaceAll("[\\r\\n]+", " ");
    }
}
