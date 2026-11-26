package com.jmr.jdms.migration.run;

import com.jmr.jdms.migration.config.MigrationProperties;
import com.jmr.jdms.migration.legacy.LegacyFileCipher;
import com.jmr.jdms.migration.legacy.LegacyFileCipher.UndecryptableFileException;
import com.jmr.jdms.migration.legacy.LegacyFileStore;
import com.jmr.jdms.migration.legacy.LegacyRepository;
import com.jmr.jdms.migration.legacy.LegacyRepository.FolderSetting;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyDocument;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyField;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyFolder;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyStep;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyUser;
import com.jmr.jdms.migration.report.Reconciliation;
import com.jmr.jdms.migration.report.Reconciliation.Checksum;
import com.jmr.jdms.migration.report.Reconciliation.Difference;
import com.jmr.jdms.migration.report.Reconciliation.DocumentResult;
import com.jmr.jdms.migration.report.Reconciliation.Outcome;
import com.jmr.jdms.migration.report.Reconciliation.PermissionCheck;
import com.jmr.jdms.migration.run.DocumentMapper.MappingException;
import com.jmr.jdms.migration.client.CoreClient;
import com.jmr.jdms.migration.client.CoreClient.CoreException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * One migration run (B-12, ADR-0020): reads every legacy document (or, in delta mode, those changed after the
 * cut-off), decrypts and checks its files, imports it through the core, reads it back, and records exactly one
 * outcome per document in the {@link Reconciliation}. A failing document never stops the run. Logs carry counts and
 * legacy IDs only: never file content, metadata values or key material.
 */
public class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);
    private static final int ACCESS_CHECK_BATCH = 500;

    private final MigrationProperties properties;
    private final LegacyRepository legacy;
    private final LegacyFileStore files;
    private final LegacyFileCipher cipher;
    private final CoreClient core;
    private final DocumentMapper mapper;

    public MigrationRunner(MigrationProperties properties, LegacyRepository legacy, LegacyFileStore files,
            LegacyFileCipher cipher, CoreClient core) {
        this.properties = properties;
        this.legacy = legacy;
        this.files = files;
        this.cipher = cipher;
        this.core = core;
        this.mapper = new DocumentMapper(properties.mapping());
    }

    /** A JDMS document type the core knows: its cabinet and field keys. */
    private record TargetType(UUID cabinetId, List<String> fieldKeys) {
    }

    public Reconciliation run() {
        Optional<java.time.Instant> since = Optional.ofNullable(properties.since());
        Reconciliation report = new Reconciliation(since.isPresent() ? "DELTA" : "FULL", properties.since());
        Map<String, TargetType> types = targetTypes();
        Map<String, FolderSetting> settings = legacy.folderSettings().stream()
                .collect(Collectors.toMap(FolderSetting::cabinet, Function.identity()));
        Map<String, List<LegacyField>> fields = legacy.fields().stream()
                .collect(Collectors.groupingBy(field -> field.cabinet() + "/" + field.docTypeId()));
        Map<UUID, Map<String, UUID>> folders = new HashMap<>();
        Map<String, String> seenContent = new HashMap<>();
        List<LegacyDocument> documents = legacy.documents(since);
        log.info("Migration run started: {} legacy documents ({})", documents.size(), since.isPresent() ? "delta"
                : "full");
        Map<String, UUID> imported = new LinkedHashMap<>();
        Map<String, LegacyDocument> byId = new HashMap<>();
        for (LegacyDocument document : documents) {
            byId.put(document.documentId(), document);
            DocumentResult result = migrate(document, types, settings, fields, folders, seenContent,
                    since.isPresent());
            report.add(result);
            if (result.documentId() != null) {
                imported.put(document.documentId(), result.documentId());
            }
        }
        comparePermissions(report, imported, byId);
        long exceptions = report.exceptions().size();
        log.info("Migration run finished: {} documents, {} exceptions", documents.size(), exceptions);
        return report;
    }

    private DocumentResult migrate(LegacyDocument document, Map<String, TargetType> types,
            Map<String, FolderSetting> settings, Map<String, List<LegacyField>> allFields,
            Map<UUID, Map<String, UUID>> folders, Map<String, String> seenContent, boolean delta) {
        String typeCode = mapper.typeCode(document);
        String cabinet = document.cabinet();
        List<String> warnings = new ArrayList<>();
        List<LegacyStep> steps = List.of();
        int legacyVersions = document.docVersion();
        try {
            mapper.requireMigratable(document);
            TargetType type = types.get(typeCode);
            if (type == null) {
                throw new MappingException(Outcome.TYPE_NOT_MIGRATED, "Document type " + typeCode
                        + " does not exist in JDMS Next (run the catalog step and approve it)");
            }
            List<LegacyField> fields = allFields.getOrDefault(cabinet + "/" + document.docType(), List.of());
            Map<String, Object> metadata = mapper.metadata(document, typeCode, fields);
            steps = legacy.history(document.documentId());
            metadata.put("history", mapper.history(steps));
            metadata.put("permissions", mapper.permissions(document));
            UUID folderId = folderId(type.cabinetId(), document.folderPath(), folders);
            if (folderId != null) {
                metadata.put("folderId", folderId.toString());
            } else if (document.folderPath() != null && !document.folderPath().isBlank()) {
                warnings.add("FOLDER_NOT_FOUND: " + document.folderPath());
            }

            List<byte[]> content = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<Map<String, Object>> versions = new ArrayList<>();
            List<String> legacySha = new ArrayList<>();
            readVersions(document, settings.get(cabinet), content, names);
            for (int i = 0; i < content.size(); i++) {
                String sha = sha256(content.get(i));
                legacySha.add(sha);
                boolean current = i == content.size() - 1;
                Map<String, Object> version = new LinkedHashMap<>();
                version.put("fileName", names.get(i));
                version.put("mimeType", mimeType(document.extension()));
                version.put("sha256", sha);
                version.put("createdAt", current && document.makeDate() != null ? document.makeDate().toString()
                        : null);
                version.put("createdBy", current ? document.maker() : null);
                versions.add(version);
            }
            metadata.put("versions", versions);
            String duplicateOf = seenContent.putIfAbsent(legacySha.getLast(), document.documentId());
            if (duplicateOf != null) {
                warnings.add("DUPLICATE_CONTENT: same file as legacy document " + duplicateOf);
            }

            JsonNode result = core.importDocument(metadata, content, names);
            UUID documentId = UUID.fromString(result.path("documentId").asString());
            List<Checksum> checksums = checksums(legacySha, result.path("versions"));
            int jdmsVersions = result.path("versions").size();
            int jdmsHistory = result.path("historyRecords").asInt();
            boolean already = result.path("alreadyImported").asBoolean();
            Outcome outcome = already ? (delta ? Outcome.CHANGED_AFTER_MIGRATION : Outcome.ALREADY_MIGRATED)
                    : Outcome.MIGRATED;
            if (checksums.stream().anyMatch(checksum -> !checksum.match())) {
                outcome = Outcome.CHECKSUM_MISMATCH;
            }
            List<Difference> differences = differences(metadata, core.document(documentId));
            String reason = outcome == Outcome.CHANGED_AFTER_MIGRATION
                    ? "Changed in JDMS after it was migrated: review and apply the change in JDMS Next" : null;
            return new DocumentResult(document.documentId(), cabinet, typeCode, outcome, documentId, legacyVersions,
                    jdmsVersions, steps.size(), jdmsHistory, checksums, differences, warnings, reason);
        } catch (MappingException e) {
            return failed(document, typeCode, e.outcome(), e.getMessage(), warnings, steps.size());
        } catch (UndecryptableFileException e) {
            return failed(document, typeCode, Outcome.UNDECRYPTABLE, e.getMessage(), warnings, steps.size());
        } catch (CoreException e) {
            Outcome outcome = switch (e.code()) {
                case "VALIDATION_FAILED" -> Outcome.INVALID_METADATA;
                case "CHECKSUM_MISMATCH" -> Outcome.CHECKSUM_MISMATCH;
                default -> Outcome.FAILED;
            };
            String reason = e.code() + (e.violations().isEmpty() ? "" : " " + e.violations());
            return failed(document, typeCode, outcome, reason, warnings, steps.size());
        } catch (RuntimeException e) {
            log.warn("Legacy document {} failed: {}", document.documentId(), e.getClass().getSimpleName());
            return failed(document, typeCode, Outcome.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    warnings, steps.size());
        }
    }

    /** Older versions from the version folder (1 .. n-1), then the current file (base or delete folder). */
    private void readVersions(LegacyDocument document, FolderSetting setting, List<byte[]> content,
            List<String> names) {
        if (setting == null) {
            throw new MappingException(Outcome.UNMAPPED, "No folder setting for legacy cabinet " + document.cabinet());
        }
        for (int n = 1; n < document.docVersion(); n++) {
            String name = mapper.versionFileName(document.fileName(), n);
            content.add(decrypted(files.resolve(setting.versionFolder(), document.folderPath(), name),
                    document.extension()));
            names.add(document.fileName());
        }
        String base = mapper.deleted(document) ? setting.deleteFolder() : setting.baseFolder();
        content.add(decrypted(files.resolve(base, document.folderPath(), document.fileName()), document.extension()));
        names.add(document.fileName());
    }

    private byte[] decrypted(Path path, String extension) {
        byte[] encrypted = files.read(path).orElseThrow(() -> new MappingException(Outcome.MISSING_FILE,
                "Missing legacy file " + files.relative(path)));
        byte[] plain = cipher.decrypt(encrypted);
        if (!ContentSignatures.matches(extension, plain)) {
            throw new MappingException(Outcome.CORRUPT, "Decrypted file " + files.relative(path)
                    + " is not a valid ." + extension + " file");
        }
        return plain;
    }

    private UUID folderId(UUID cabinetId, String path, Map<UUID, Map<String, UUID>> folders) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Map<String, UUID> byPath = folders.computeIfAbsent(cabinetId, id -> {
            Map<String, UUID> map = new HashMap<>();
            core.folders(id).forEach(folder -> map.put(normalize(folder.path("path").asString()),
                    UUID.fromString(folder.path("id").asString())));
            return map;
        });
        return byPath.get(normalize(path));
    }

    private Map<String, TargetType> targetTypes() {
        Map<String, TargetType> types = new HashMap<>();
        core.documentTypes().forEach(type -> {
            List<String> keys = new ArrayList<>();
            type.path("fields").forEach(field -> keys.add(field.path("key").asString()));
            types.put(type.path("code").asString(), new TargetType(UUID.fromString(type.path("cabinetId")
                    .asString()), keys));
        });
        return types;
    }

    /** Legacy actions of sampled users per migrated document, against what JDMS Next decides for the same user. */
    private void comparePermissions(Reconciliation report, Map<String, UUID> imported,
            Map<String, LegacyDocument> documents) {
        if (imported.isEmpty()) {
            return;
        }
        List<LegacyUser> users = legacy.users(properties.sampleUsers());
        List<LegacyFolder> legacyFolders = legacy.folders();
        Map<UUID, String> legacyIds = imported.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        List<UUID> ids = List.copyOf(imported.values());
        for (LegacyUser user : users) {
            List<String> roles = mapper.jdmsRoles(user.roles());
            for (int from = 0; from < ids.size(); from += ACCESS_CHECK_BATCH) {
                List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + ACCESS_CHECK_BATCH));
                JsonNode decided = core.accessCheck(roles, user.branchCode(),
                        properties.mapping().sampleClearance(), batch);
                for (UUID id : batch) {
                    LegacyDocument document = documents.get(legacyIds.get(id));
                    List<String> legacyActions = mapper.legacyActions(user.roles(), document, legacyFolders);
                    List<String> jdmsActions = new ArrayList<>();
                    decided.path(id.toString()).forEach(action -> jdmsActions.add(action.asString()));
                    if (legacyActions.isEmpty() && jdmsActions.isEmpty()) {
                        continue;
                    }
                    report.add(new PermissionCheck(user.userId(), document.documentId(), legacyActions, jdmsActions,
                            legacyActions.stream().filter(action -> !jdmsActions.contains(action)).toList(),
                            jdmsActions.stream().filter(action -> !legacyActions.contains(action)).toList()));
                }
            }
        }
    }

    private static List<Checksum> checksums(List<String> legacySha, JsonNode stored) {
        List<Checksum> checksums = new ArrayList<>();
        for (int i = 0; i < legacySha.size(); i++) {
            JsonNode version = stored.path(i);
            String jdms = version.path("sha256").asString(null);
            checksums.add(new Checksum(version.path("number").asString(String.valueOf(i + 1)), legacySha.get(i), jdms,
                    legacySha.get(i).equals(jdms)));
        }
        return checksums;
    }

    /** Field by field: what was sent from the legacy row against what the core stored. */
    private static List<Difference> differences(Map<String, Object> sent, JsonNode stored) {
        List<Difference> differences = new ArrayList<>();
        for (String field : List.of("title", "cif", "customerName", "accountNo", "contractRef", "expiryDate")) {
            compare(field, sent.get(field), stored.path(field), differences);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> custom = (Map<String, Object>) sent.getOrDefault("customFields", Map.of());
        custom.forEach((key, value) -> compare("customFields." + key, value, stored.path("customFields").path(key),
                differences));
        return differences;
    }

    private static void compare(String field, Object legacy, JsonNode stored, List<Difference> differences) {
        if (legacy == null) {
            return; // nothing sent: the core may fill a default (e.g. the title)
        }
        String jdms = stored.isMissingNode() || stored.isNull() ? null : stored.asString();
        if (!same(legacy, jdms)) {
            differences.add(new Difference(field, legacy.toString(), jdms));
        }
    }

    private static boolean same(Object legacy, String jdms) {
        if (jdms == null) {
            return false;
        }
        if (legacy instanceof BigDecimal number) {
            try {
                return number.compareTo(new BigDecimal(jdms)) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return Objects.equals(legacy.toString(), jdms);
    }

    private DocumentResult failed(LegacyDocument document, String typeCode, Outcome outcome, String reason,
            List<String> warnings, int legacyHistory) {
        log.info("Legacy document {} not migrated: {}", document.documentId(), outcome);
        return new DocumentResult(document.documentId(), document.cabinet(), typeCode, outcome, null,
                document.docVersion(), 0, legacyHistory, 0, List.of(), List.of(), warnings, reason);
    }

    private static String normalize(String path) {
        String clean = path.replace('\\', '/').replaceAll("/+", "/");
        clean = clean.startsWith("/") ? clean : "/" + clean;
        return clean.length() > 1 && clean.endsWith("/") ? clean.substring(0, clean.length() - 1) : clean;
    }

    private static String mimeType(String extension) {
        return switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
            case "pdf" -> "application/pdf";
            case "tif", "tiff" -> "image/tiff";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "application/octet-stream";
        };
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
