package com.jmr.jdms.migration.run;

import com.jmr.jdms.migration.config.MigrationProperties.Mapping;
import com.jmr.jdms.migration.legacy.LegacyRepository;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyField;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyFolder;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyType;
import com.jmr.jdms.migration.report.Reconciliation;
import com.jmr.jdms.migration.run.DocumentMapper.MappingException;
import com.jmr.jdms.migration.client.CoreClient;
import com.jmr.jdms.migration.client.CoreClient.CoreException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * The catalog step (ADR-0020): legacy document types with their fields, folders and folder permissions become
 * proposals in the core's administration API. Nothing is created directly: the bank's administrators approve each
 * proposal (maker-checker) before documents are imported. Run again after approvals for nested folders and for the
 * permissions of newly approved folders; existing items are never proposed twice.
 */
public class CatalogProposer {

    private final LegacyRepository legacy;
    private final CoreClient core;
    private final DocumentMapper mapper;
    private final Mapping mapping;

    public CatalogProposer(LegacyRepository legacy, CoreClient core, Mapping mapping) {
        this.legacy = legacy;
        this.core = core;
        this.mapping = mapping;
        this.mapper = new DocumentMapper(mapping);
    }

    public Reconciliation run() {
        Reconciliation report = new Reconciliation("CATALOG", null);
        Map<String, UUID> cabinets = new HashMap<>();
        core.cabinets().forEach(cabinet -> cabinets.put(cabinet.path("code").asString(),
                UUID.fromString(cabinet.path("id").asString())));
        Set<String> types = new HashSet<>();
        core.documentTypes().forEach(type -> types.add(type.path("code").asString()));
        Map<String, List<LegacyField>> fields = legacy.fields().stream()
                .collect(Collectors.groupingBy(field -> field.cabinet() + "/" + field.docTypeId()));

        for (LegacyType type : legacy.types()) {
            String code = mapping.types().getOrDefault(type.cabinet() + "/" + type.docTypeId(),
                    type.docTypeId().toUpperCase(Locale.ROOT));
            if (types.contains(code)) {
                report.catalog("Document type " + code + ": exists");
                continue;
            }
            UUID cabinetId = cabinets.get(mapper.cabinetCode(type.cabinet()));
            if (cabinetId == null) {
                report.catalog("Document type " + code + ": cabinet " + mapper.cabinetCode(type.cabinet())
                        + " does not exist; create it first");
                continue;
            }
            propose(report, "Document type " + code, "/api/v1/admin/document-types", documentType(cabinetId, code,
                    type, fields.getOrDefault(type.cabinet() + "/" + type.docTypeId(), List.of())));
        }

        for (LegacyFolder folder : legacy.folders()) {
            UUID cabinetId = cabinets.get(mapper.cabinetCode(folder.cabinet()));
            if (cabinetId == null) {
                report.catalog("Folder " + folder.path() + ": cabinet does not exist");
                continue;
            }
            Map<String, UUID> existing = new HashMap<>();
            core.folders(cabinetId).forEach(node -> existing.put(node.path("path").asString(),
                    UUID.fromString(node.path("id").asString())));
            String path = folder.path().startsWith("/") ? folder.path() : "/" + folder.path();
            UUID folderId = existing.get(path);
            if (folderId == null) {
                String parentPath = path.substring(0, path.lastIndexOf('/'));
                UUID parentId = parentPath.isEmpty() ? null : existing.get(parentPath);
                if (!parentPath.isEmpty() && parentId == null) {
                    report.catalog("Folder " + path + ": waits for its parent; run the catalog step again after "
                            + "approval");
                    continue;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("parentId", parentId == null ? null : parentId.toString());
                body.put("name", path.substring(path.lastIndexOf('/') + 1));
                propose(report, "Folder " + path, "/api/v1/admin/cabinets/" + cabinetId + "/folders", body);
                continue;
            }
            if (folder.roleId() != null && !folder.roleId().isBlank()) {
                try {
                    String role = mapping.roles().get(folder.roleId());
                    if (role == null) {
                        throw new MappingException(Reconciliation.Outcome.UNMAPPED, "legacy role " + folder.roleId()
                                + " has no mapping");
                    }
                    Map<String, Object> rule = new LinkedHashMap<>();
                    rule.put("scopeType", "FOLDER");
                    rule.put("scopeId", folderId.toString());
                    rule.put("principalType", "ROLE");
                    rule.put("principalId", role);
                    rule.put("actions", mapper.actions(folder.permission()));
                    rule.put("effect", "ALLOW");
                    rule.put("branchConstraint", "ALL");
                    rule.put("description", "Migrated from legacy JDMS folder permission (legacy role "
                            + folder.roleId() + ")");
                    propose(report, "Permission of " + role + " on folder " + path, "/api/v1/admin/access-rules",
                            rule);
                } catch (MappingException e) {
                    report.catalog("Permission on folder " + path + ": " + e.getMessage());
                }
            }
        }
        return report;
    }

    private Map<String, Object> documentType(UUID cabinetId, String code, LegacyType type, List<LegacyField> legacy) {
        List<Map<String, Object>> fieldSpecs = new ArrayList<>();
        for (LegacyField field : legacy) {
            Map<String, Object> spec = new LinkedHashMap<>();
            String dataType = DocumentMapper.dataType(field.fieldId());
            spec.put("key", DocumentMapper.key(field.fieldName()));
            spec.put("labelEn", field.label() == null ? field.fieldName() : field.label());
            // legacy JDMS has no Arabic labels: administrators translate before approving
            spec.put("labelAr", field.label() == null ? field.fieldName() : field.label());
            spec.put("dataType", dataType);
            spec.put("mandatory", field.mandatory());
            spec.put("maxLength", "TEXT".equals(dataType) ? field.length() : null);
            spec.put("searchable", true);
            spec.put("facet", false);
            spec.put("sensitive", false);
            spec.put("displayOrder", field.order());
            spec.put("displayRow", field.order());
            fieldSpecs.add(spec);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cabinetId", cabinetId.toString());
        body.put("code", code);
        body.put("nameEn", type.description() == null ? code : type.description());
        body.put("nameAr", type.description() == null ? code : type.description());
        body.put("category", type.category() == null ? "LEGACY" : type.category());
        body.put("channels", List.of("UI", "INTEGRATION"));
        body.put("defaultClassification", "CONFIDENTIAL");
        body.put("processKey", "single-approval");
        body.put("metaChangeNeedsApproval", true);
        body.put("deleteRequiresApproval", true);
        body.put("declareRecordOnApproval", false);
        body.put("requiredStandardFields", List.of());
        body.put("fields", fieldSpecs);
        return body;
    }

    private void propose(Reconciliation report, String what, String path, Map<String, Object> body) {
        try {
            JsonNode change = core.propose(path, body);
            report.catalog(what + ": proposed (pending change " + change.path("id").asString() + ")");
        } catch (CoreException e) {
            report.catalog(what + ": refused " + e.code() + (e.violations().isEmpty() ? "" : " " + e.violations()));
        }
    }
}
