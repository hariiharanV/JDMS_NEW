package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.workflow.api.ApprovalProcesses;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Shared plumbing for the metadata change handlers: payload decoding, common checks and audit of applied changes. */
@Component
class MetadataChangeSupport {

    static final String CODE_IN_USE = "CODE_IN_USE";
    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,29}");

    private final JsonMapper jsonMapper;
    private final AuditTrail auditTrail;
    private final AccessPolicy accessPolicy;

    MetadataChangeSupport(JsonMapper jsonMapper, AuditTrail auditTrail, AccessPolicy accessPolicy) {
        this.jsonMapper = jsonMapper;
        this.auditTrail = auditTrail;
        this.accessPolicy = accessPolicy;
    }

    /** Whether the current caller may decide metadata changes ({@code ADMIN_METADATA} on the tenant). */
    boolean canAdministerMetadata() {
        Subject subject = Subject.current();
        return accessPolicy.decide(subject, Action.ADMIN_METADATA, new ResourceRef.Tenant(subject.tenantId()))
                .allowed();
    }

    <T> T payload(PendingChangeView change, Class<T> type) {
        return jsonMapper.treeToValue(change.payload(), type);
    }

    /** Audits an applied change; the actor is the approver, the proposer is in the details. */
    void auditApplied(String action, String resourceType, Object resourceId, PendingChangeView change,
            Map<String, Object> details) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("changeId", change.id().toString());
        all.put("proposedBy", change.proposedBy().toString());
        all.putAll(details);
        auditTrail.record(AuditEntry.success(action, resourceType, resourceId, all));
    }

    static ApiException codeInUse(String what) {
        return new ApiException(HttpStatus.CONFLICT, CODE_IN_USE, "A " + what + " with this code already exists.");
    }

    static void checkCode(String code, List<Violation> violations) {
        if (code == null || !CODE.matcher(code).matches()) {
            violations.add(new Violation("code", "CODE_INVALID", "Code must be 2-30 of A-Z, 0-9 and _, starting with a letter."));
        }
    }

    static void checkName(String field, String value, int max, List<Violation> violations) {
        if (value == null || value.isBlank() || value.length() > max) {
            violations.add(new Violation(field, "NAME_INVALID", "Required, at most " + max + " characters."));
        }
    }

    /** Names in a second language (Arabic) may be left out; given, they follow the same length limit. */
    static void checkOptionalName(String field, String value, int max, List<Violation> violations) {
        if (value != null && value.length() > max) {
            violations.add(new Violation(field, "NAME_INVALID", "At most " + max + " characters."));
        }
    }

    /** A trimmed optional name; blank becomes {@code null}, so screens fall back to the English name. */
    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static void checkProcessKey(String field, String processKey, List<Violation> violations) {
        if (processKey != null && !ApprovalProcesses.KEYS.contains(processKey)) {
            violations.add(new Violation(field, "PROCESS_UNKNOWN",
                    "Must be one of " + String.join(", ", ApprovalProcesses.KEYS.stream().sorted().toList()) + "."));
        }
    }

    static String describe(List<Violation> violations) {
        return violations.stream().map(v -> v.field() + ": " + v.message()).reduce((a, b) -> a + "; " + b)
                .orElse("invalid");
    }
}
