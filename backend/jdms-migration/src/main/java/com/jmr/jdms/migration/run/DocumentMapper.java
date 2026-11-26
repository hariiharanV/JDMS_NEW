package com.jmr.jdms.migration.run;

import com.jmr.jdms.migration.config.MigrationProperties.Mapping;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyDocument;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyField;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyFolder;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyStep;
import com.jmr.jdms.migration.report.Reconciliation.Outcome;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps legacy rows onto the import request (reference §5, ADR-0020). Pure: no I/O. Anything that cannot be mapped
 * faithfully stops the document with a reason ({@link MappingException}); nothing is dropped quietly.
 */
public class DocumentMapper {

    private final Mapping mapping;

    public DocumentMapper(Mapping mapping) {
        this.mapping = mapping;
    }

    /** A legacy document that cannot be migrated as it is, and why. */
    public static final class MappingException extends RuntimeException {

        private final Outcome outcome;

        public MappingException(Outcome outcome, String reason) {
            super(reason, null, false, false);
            this.outcome = outcome;
        }

        public Outcome outcome() {
            return outcome;
        }
    }

    public String typeCode(LegacyDocument document) {
        return mapping.types().getOrDefault(document.cabinet() + "/" + document.docType(),
                document.docType() == null ? null : document.docType().toUpperCase(Locale.ROOT));
    }

    public String cabinetCode(String legacyCabinet) {
        return mapping.cabinets().getOrDefault(legacyCabinet, legacyCabinet.toUpperCase(Locale.ROOT));
    }

    public boolean deleted(LegacyDocument document) {
        return document.status() != null && Set.of("DELETED", "D").contains(document.status().toUpperCase(Locale.ROOT));
    }

    /** Stops documents still in approval or rejected: they are finished in JDMS before cut-over (reference §5). */
    public void requireMigratable(LegacyDocument document) {
        String workflow = document.wfStatus() == null ? "" : document.wfStatus().toUpperCase(Locale.ROOT);
        if (mapping.inWorkflow().contains(workflow)) {
            throw new MappingException(Outcome.IN_WORKFLOW, "Still in legacy approval (" + workflow
                    + "): finish it in JDMS before cut-over");
        }
        if ("REJECTED".equals(workflow)) {
            throw new MappingException(Outcome.UNMAPPED, "Rejected in JDMS: not migrated without a decision");
        }
    }

    /** The import metadata without versions, folder and permissions (added by the runner). */
    public Map<String, Object> metadata(LegacyDocument document, String typeCode, List<LegacyField> fields) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("legacyDocumentId", document.documentId());
        metadata.put("status", deleted(document) ? "DELETED" : "ACTIVE");
        metadata.put("createdAt", document.createdAt() == null ? null : document.createdAt().toString());
        metadata.put("createdBy", document.maker());
        metadata.put("documentTypeCode", typeCode);
        metadata.put("title", blankToNull(document.title()));
        metadata.put("branchCode", document.branchCode());
        metadata.put("cif", blankToNull(document.customerId()));
        metadata.put("customerName", blankToNull(document.customerName()));
        metadata.put("accountNo", blankToNull(document.accountNo()));
        metadata.put("contractRef", blankToNull(document.contractRef()));
        metadata.put("expiryDate", document.expiryDate() == null ? null : document.expiryDate().toString());
        metadata.put("customFields", customFields(document, fields));
        return metadata;
    }

    /** Custom field slots ({@code cust_str_fieldN}, {@code cust_dt_fieldN}, {@code cust_num_fieldN}) by field key. */
    public Map<String, Object> customFields(LegacyDocument document, List<LegacyField> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (LegacyField field : fields) {
            Object raw = document.slots().get(field.fieldId().toLowerCase(Locale.ROOT));
            Object value = convert(field.fieldId().toLowerCase(Locale.ROOT), raw);
            if (value != null) {
                values.put(key(field.fieldName()), value);
            }
        }
        return values;
    }

    /** JDMS field key of a legacy field name: lower case, letters, digits and underscores. */
    public static String key(String fieldName) {
        String key = fieldName.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return key.isEmpty() || !Character.isLetter(key.charAt(0)) ? "f_" + key : key;
    }

    /** The JDMS data type of a legacy slot. */
    public static String dataType(String fieldId) {
        String slot = fieldId.toLowerCase(Locale.ROOT);
        return slot.startsWith("cust_dt_") ? "DATE" : slot.startsWith("cust_num_") ? "NUMBER" : "TEXT";
    }

    /** Legacy workflow steps as approval history; each return starts a new round. */
    public List<Map<String, Object>> history(List<LegacyStep> steps) {
        List<Map<String, Object>> history = new ArrayList<>();
        int round = 1;
        for (LegacyStep step : steps) {
            String action = step.action() == null ? "" : step.action().strip().toUpperCase(Locale.ROOT);
            String decision = mapping.decisions().get(action);
            if (decision == null) {
                throw new MappingException(Outcome.UNMAPPED, "Legacy workflow action '" + action + "' has no mapping");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("round", round);
            entry.put("stage", step.stage());
            entry.put("actor", step.user());
            entry.put("decision", decision);
            entry.put("comment", blankToNull(step.remarks()));
            entry.put("decidedAt", step.at() == null ? null : step.at().toString());
            history.add(entry);
            if ("RETURN".equals(decision)) {
                round++;
            }
        }
        return history;
    }

    /** The document's legacy role permission as JDMS rules ({@code role_id} + {@code doc_permission} letters). */
    public List<Map<String, Object>> permissions(LegacyDocument document) {
        if (document.roleId() == null || document.roleId().isBlank()) {
            return List.of();
        }
        String role = mapping.roles().get(document.roleId());
        if (role == null) {
            throw new MappingException(Outcome.UNMAPPED, "Legacy role '" + document.roleId() + "' has no mapping");
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("role", role);
        rule.put("legacyRole", document.roleId());
        rule.put("actions", actions(document.permission()));
        return List.of(rule);
    }

    /** JDMS actions of legacy permission letters (e.g. {@code VD}). */
    public List<String> actions(String letters) {
        Set<String> actions = new LinkedHashSet<>();
        if (letters != null) {
            for (char letter : letters.strip().toUpperCase(Locale.ROOT).toCharArray()) {
                List<String> mapped = mapping.permissions().get(String.valueOf(letter));
                if (mapped == null) {
                    throw new MappingException(Outcome.UNMAPPED, "Legacy permission '" + letter
                            + "' has no mapping");
                }
                actions.addAll(mapped);
            }
        }
        return List.copyOf(actions);
    }

    /** What a legacy user could do with the document: the permission of the document's role, if the user has it. */
    public List<String> legacyActions(List<String> userRoles, LegacyDocument document) {
        return legacyActions(userRoles, document, List.of());
    }

    /**
     * What a legacy user could do with the document: the document's role permission and the permissions of the
     * legacy folders holding it (the folder and its ancestors in the same cabinet), for the roles the user has.
     */
    public List<String> legacyActions(List<String> userRoles, LegacyDocument document, List<LegacyFolder> folders) {
        Set<String> actions = new LinkedHashSet<>();
        if (document.roleId() != null && userRoles.contains(document.roleId())) {
            actions.addAll(actions(document.permission()));
        }
        String path = folderPath(document.folderPath());
        for (LegacyFolder folder : folders) {
            String folderPath = folderPath(folder.path());
            if (folder.cabinet().equals(document.cabinet()) && folder.roleId() != null
                    && userRoles.contains(folder.roleId()) && path != null
                    && (path.equals(folderPath) || path.startsWith(folderPath + "/"))) {
                actions.addAll(actions(folder.permission()));
            }
        }
        return List.copyOf(actions);
    }

    private static String folderPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String clean = ("/" + path.replace('\\', '/')).replaceAll("/+", "/");
        return clean.length() > 1 && clean.endsWith("/") ? clean.substring(0, clean.length() - 1) : clean;
    }

    /** JDMS roles of a legacy user (unmapped legacy roles carry no JDMS access). */
    public List<String> jdmsRoles(List<String> legacyRoles) {
        return legacyRoles.stream().map(mapping.roles()::get).filter(role -> role != null).distinct().toList();
    }

    /** File name of an older version in the version folder. */
    public String versionFileName(String fileName, int number) {
        return mapping.versionFile().replace("{name}", fileName).replace("{n}", Integer.toString(number));
    }

    private static Object convert(String slot, Object raw) {
        if (raw == null) {
            return null;
        }
        if (slot.startsWith("cust_dt_")) {
            if (raw instanceof Timestamp timestamp) {
                return timestamp.toLocalDateTime().toLocalDate().toString();
            }
            if (raw instanceof java.sql.Date date) {
                return date.toLocalDate().toString();
            }
            if (raw instanceof LocalDate date) {
                return date.toString();
            }
            return blankToNull(raw.toString());
        }
        if (slot.startsWith("cust_num_")) {
            return raw instanceof BigDecimal number ? number.stripTrailingZeros() : new BigDecimal(raw.toString().strip())
                    .stripTrailingZeros();
        }
        return blankToNull(raw.toString().strip());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
