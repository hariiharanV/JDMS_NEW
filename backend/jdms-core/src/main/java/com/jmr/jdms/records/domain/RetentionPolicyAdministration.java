package com.jmr.jdms.records.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.metadata.api.RetentionAssignments;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.security.Roles;
import com.jmr.jdms.workflow.api.ApplyResult;
import com.jmr.jdms.workflow.api.ChangeHandler;
import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.ChangeProposal;
import com.jmr.jdms.workflow.api.ChangeRequests;
import com.jmr.jdms.workflow.api.PendingChangeView;
import com.jmr.jdms.workflow.api.TenantPermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retention policies and the document types they govern (spec §4.8). Changes are proposals approved by a second
 * holder of {@code MANAGE_RETENTION} (maker-checker, ADR-0009).
 */
@Service
public class RetentionPolicyAdministration implements ChangeHandler {

    public static final String SUBJECT = "RETENTION_POLICY";
    public static final String POLICY_NOT_FOUND = "RETENTION_POLICY_NOT_FOUND";
    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,29}");
    static final Pattern EVENT = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");

    private final RetentionPolicyRepository policies;
    private final RetentionAssignments assignments;
    private final MetadataCatalog catalog;
    private final ChangeRequests changeRequests;
    private final TenantPermissions permissions;
    private final AuditTrail auditTrail;
    private final JsonMapper jsonMapper;

    RetentionPolicyAdministration(RetentionPolicyRepository policies, RetentionAssignments assignments,
            MetadataCatalog catalog, ChangeRequests changeRequests, TenantPermissions permissions,
            AuditTrail auditTrail, JsonMapper jsonMapper) {
        this.policies = policies;
        this.assignments = assignments;
        this.catalog = catalog;
        this.changeRequests = changeRequests;
        this.permissions = permissions;
        this.auditTrail = auditTrail;
        this.jsonMapper = jsonMapper;
    }

    /** {@code code} only on create; {@code documentTypeCodes}: the types this policy governs (replaces the list). */
    public record PolicyPayload(String code, String name, RetentionTrigger triggerType, String eventType,
            Integer periodMonths, DispositionAction dispositionAction, Boolean requiresApproval,
            List<String> documentTypeCodes) {
    }

    public record PolicyView(UUID id, String code, String name, RetentionTrigger triggerType, String eventType,
            int periodMonths, DispositionAction dispositionAction, boolean requiresApproval,
            List<String> documentTypeCodes, long version) {
    }

    @Transactional(readOnly = true)
    public List<PolicyView> list() {
        return policies.findAll().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public PolicyView get(UUID id) {
        return view(load(id));
    }

    @Transactional
    public PendingChangeView proposeCreate(PolicyPayload payload) {
        List<Violation> violations = violations(payload);
        if (payload.code() == null || !CODE.matcher(payload.code()).matches()) {
            violations.add(new Violation("code", "CODE_INVALID", "2-30 of A-Z, 0-9 and _, starting with a letter."));
        }
        ValidationFailedException.throwIfAny("The retention policy is invalid.", violations);
        if (policies.existsByCode(payload.code())) {
            throw new ApiException(HttpStatus.CONFLICT, "CODE_IN_USE", "A retention policy with this code exists.");
        }
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.CREATE, Ids.newId(), payload.code(),
                null, normalise(payload, payload.code()), "Create retention policy " + payload.code()));
    }

    @Transactional
    public PendingChangeView proposeUpdate(UUID id, long expectedVersion, PolicyPayload payload) {
        RetentionPolicy policy = load(id);
        if (policy.getVersionLock() != expectedVersion) {
            throw ApiException.staleVersion();
        }
        ValidationFailedException.throwIfAny("The retention policy is invalid.", violations(payload));
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE, id, null, expectedVersion,
                normalise(payload, policy.getCode()), "Update retention policy " + policy.getCode()));
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Set<String> candidateRoles() {
        return Set.of(Roles.RECORDS_OFFICER);
    }

    @Override
    public boolean canDecide(PendingChangeView change) {
        return permissions.allowed("MANAGE_RETENTION");
    }

    @Override
    public ApplyResult apply(PendingChangeView change) {
        PolicyPayload payload = jsonMapper.treeToValue(change.payload(), PolicyPayload.class);
        Set<UUID> types = new HashSet<>();
        for (String code : payload.documentTypeCodes()) {
            DocumentTypeInfo type = catalog.findDocumentType(code).orElse(null);
            if (type == null) {
                return ApplyResult.stale("Document type " + code + " no longer exists.");
            }
            types.add(type.id());
        }
        RetentionPolicy policy;
        if (change.operation() == ChangeOperation.CREATE) {
            if (policies.existsByCode(payload.code())) {
                return ApplyResult.stale("A retention policy with code " + payload.code() + " was created meanwhile.");
            }
            policy = policies.save(new RetentionPolicy(change.subjectId(), payload.code(), payload.name(),
                    payload.triggerType(), payload.eventType(), payload.periodMonths(), payload.dispositionAction(),
                    payload.requiresApproval()));
        } else {
            policy = policies.findById(change.subjectId()).orElse(null);
            if (policy == null || policy.getVersionLock() != change.baseVersion()) {
                return ApplyResult.stale("The retention policy was changed after this change was proposed.");
            }
            policy.update(payload.name(), payload.triggerType(), payload.eventType(), payload.periodMonths(),
                    payload.dispositionAction(), payload.requiresApproval());
        }
        policies.flush();
        assignments.assign(policy.getId(), types);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changeId", change.id().toString());
        details.put("proposedBy", change.proposedBy().toString());
        details.put("code", policy.getCode());
        details.put("documentTypeCodes", payload.documentTypeCodes());
        auditTrail.record(AuditEntry.success(change.operation() == ChangeOperation.CREATE
                ? "RETENTION_POLICY_CREATED" : "RETENTION_POLICY_UPDATED", SUBJECT, policy.getId(), details));
        return ApplyResult.ok();
    }

    private List<Violation> violations(PolicyPayload payload) {
        List<Violation> violations = new ArrayList<>();
        if (payload.name() == null || payload.name().isBlank() || payload.name().length() > 200) {
            violations.add(new Violation("name", "NAME_INVALID", "Required, at most 200 characters."));
        }
        if (payload.triggerType() == null) {
            violations.add(new Violation("triggerType", "TRIGGER_REQUIRED", "CREATION_DATE, EXPIRY_DATE or EVENT."));
        } else if (payload.triggerType() == RetentionTrigger.EVENT
                && (payload.eventType() == null || !EVENT.matcher(payload.eventType()).matches())) {
            violations.add(new Violation("eventType", "EVENT_TYPE_REQUIRED", "Event-based policies name the event."));
        } else if (payload.triggerType() != RetentionTrigger.EVENT && payload.eventType() != null) {
            violations.add(new Violation("eventType", "EVENT_TYPE_NOT_ALLOWED", "Only for event-based policies."));
        }
        if (payload.periodMonths() == null || payload.periodMonths() < 0 || payload.periodMonths() > 1200) {
            violations.add(new Violation("periodMonths", "PERIOD_INVALID", "0 to 1200 months."));
        }
        if (payload.dispositionAction() == null) {
            violations.add(new Violation("dispositionAction", "ACTION_REQUIRED", "DESTROY or REVIEW."));
        }
        for (String code : payload.documentTypeCodes() == null ? List.<String>of() : payload.documentTypeCodes()) {
            if (catalog.findDocumentType(code).isEmpty()) {
                violations.add(new Violation("documentTypeCodes", "DOCUMENT_TYPE_UNKNOWN", "Unknown type " + code));
            }
        }
        return violations;
    }

    private PolicyPayload normalise(PolicyPayload payload, String code) {
        return new PolicyPayload(code, payload.name().trim(), payload.triggerType(), payload.eventType(),
                payload.periodMonths(), payload.dispositionAction(),
                payload.requiresApproval() == null || payload.requiresApproval(),
                payload.documentTypeCodes() == null ? List.of() : List.copyOf(payload.documentTypeCodes()));
    }

    private PolicyView view(RetentionPolicy policy) {
        List<String> types = assignments.typesOf(policy.getId()).stream()
                .map(id -> catalog.findDocumentType(id).map(DocumentTypeInfo::code).orElse(null))
                .filter(Objects::nonNull).sorted().collect(Collectors.toList());
        return new PolicyView(policy.getId(), policy.getCode(), policy.getName(), policy.getTriggerType(),
                policy.getEventType(), policy.getPeriodMonths(), policy.getDispositionAction(),
                policy.isRequiresApproval(), types, policy.getVersionLock());
    }

    private RetentionPolicy load(UUID id) {
        return policies.findById(id).orElseThrow(() -> ApiException.notFound(POLICY_NOT_FOUND,
                "Retention policy not found."));
    }
}
