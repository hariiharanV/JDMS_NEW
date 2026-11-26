package com.jmr.jdms.metadata.domain;

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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists of values. An update replaces the value list: values are matched by code (codes never change), values left
 * out are deactivated rather than removed, because documents may hold them. A LOV used by any field cannot be
 * deleted, and must keep at least one active value.
 */
@Service
public class LovAdministration implements ChangeHandler {

    public static final String SUBJECT = "LOV";
    public static final String LOV_NOT_FOUND = "LOV_NOT_FOUND";
    public static final String LOV_IN_USE = "LOV_IN_USE";

    private static final Pattern VALUE_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_]{0,49}");

    private final LovRepository lovs;
    private final LovValueRepository values;
    private final FieldDefinitionRepository fields;
    private final ChangeRequests changeRequests;
    private final MetadataChangeSupport support;
    private final EntityManager entityManager;

    LovAdministration(LovRepository lovs, LovValueRepository values, FieldDefinitionRepository fields,
            ChangeRequests changeRequests, MetadataChangeSupport support, EntityManager entityManager) {
        this.lovs = lovs;
        this.values = values;
        this.fields = fields;
        this.changeRequests = changeRequests;
        this.support = support;
        this.entityManager = entityManager;
    }

    public record LovValuePayload(String code, String labelEn, String labelAr, int displayOrder, boolean active) {
    }

    public record LovPayload(String code, String name, List<LovValuePayload> values) {
    }

    public record LovView(UUID id, String code, String name, long version, List<LovValuePayload> values) {
    }

    @Transactional(readOnly = true)
    public Page<LovView> list(Pageable pageable) {
        return lovs.findAll(pageable).map(lov -> new LovView(lov.getId(), lov.getCode(), lov.getName(),
                lov.getVersionLock(), null));
    }

    @Transactional(readOnly = true)
    public LovView get(UUID id) {
        Lov lov = load(id);
        return new LovView(lov.getId(), lov.getCode(), lov.getName(), lov.getVersionLock(), valuesOf(lov.getId()));
    }

    @Transactional
    public PendingChangeView proposeCreate(LovPayload payload) {
        List<Violation> violations = new ArrayList<>();
        MetadataChangeSupport.checkCode(payload.code(), violations);
        violations.addAll(contentViolations(payload));
        ValidationFailedException.throwIfAny("The list of values is invalid.", violations);
        if (lovs.existsByCode(payload.code())) {
            throw MetadataChangeSupport.codeInUse("list of values");
        }
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.CREATE, Ids.newId(),
                payload.code(), null, normalise(payload.code(), payload),
                "Create list of values " + payload.code() + " with " + payload.values().size() + " value(s)"));
    }

    @Transactional
    public PendingChangeView proposeUpdate(UUID id, long expectedVersion, LovPayload payload) {
        Lov lov = loadAtVersion(id, expectedVersion);
        List<Violation> violations = contentViolations(payload);
        if (payload.code() != null && !payload.code().equals(lov.getCode())) {
            violations.add(new Violation("code", "CODE_IMMUTABLE", "The code cannot be changed."));
        }
        violations.addAll(usageViolations(lov, payload));
        ValidationFailedException.throwIfAny("The list of values is invalid.", violations);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE, id, null, expectedVersion,
                normalise(lov.getCode(), payload), "Update list of values " + lov.getCode()));
    }

    @Transactional
    public PendingChangeView proposeDelete(UUID id, long expectedVersion) {
        Lov lov = loadAtVersion(id, expectedVersion);
        if (fields.existsByLovCode(lov.getCode())) {
            throw new ApiException(HttpStatus.CONFLICT, LOV_IN_USE, "The list of values is used by fields.");
        }
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.DELETE, id, null, expectedVersion,
                Map.of(), "Delete list of values " + lov.getCode()));
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Set<String> candidateRoles() {
        return Set.of(Roles.TENANT_ADMIN);
    }

    @Override
    public boolean canDecide(PendingChangeView change) {
        return support.canAdministerMetadata();
    }

    @Override
    public ApplyResult apply(PendingChangeView change) {
        if (change.operation() == ChangeOperation.CREATE) {
            LovPayload payload = support.payload(change, LovPayload.class);
            if (lovs.existsByCode(payload.code())) {
                return ApplyResult.stale("A list of values with code " + payload.code()
                        + " was created in the meantime.");
            }
            Lov lov = lovs.save(new Lov(change.subjectId(), payload.code(), payload.name()));
            payload.values().forEach(value -> values.save(new LovValue(lov.getId(), value.code(), value.labelEn(),
                    value.labelAr(), value.displayOrder(), value.active())));
            support.auditApplied("LOV_CREATED", SUBJECT, lov.getId(), change, Map.of("code", lov.getCode()));
            return ApplyResult.ok();
        }
        Lov lov = lovs.findById(change.subjectId()).orElse(null);
        if (lov == null || lov.getVersionLock() != change.baseVersion()) {
            return ApplyResult.stale("The list of values was changed after this change was proposed.");
        }
        if (change.operation() == ChangeOperation.DELETE) {
            if (fields.existsByLovCode(lov.getCode())) {
                return ApplyResult.stale("The list of values is now used by fields.");
            }
            values.deleteAll(values.findByLovId(lov.getId()));
            lovs.delete(lov);
            support.auditApplied("LOV_DELETED", SUBJECT, lov.getId(), change, Map.of("code", lov.getCode()));
            return ApplyResult.ok();
        }
        LovPayload payload = support.payload(change, LovPayload.class);
        List<Violation> usage = usageViolations(lov, payload);
        if (!usage.isEmpty()) {
            return ApplyResult.stale(MetadataChangeSupport.describe(usage));
        }
        lov.rename(payload.name());
        Map<String, LovValue> existing = values.findByLovId(lov.getId()).stream()
                .collect(Collectors.toMap(LovValue::getCode, Function.identity()));
        Set<String> proposedCodes = new HashSet<>();
        for (LovValuePayload value : payload.values()) {
            proposedCodes.add(value.code());
            LovValue current = existing.get(value.code());
            if (current == null) {
                values.save(new LovValue(lov.getId(), value.code(), value.labelEn(), value.labelAr(),
                        value.displayOrder(), value.active()));
            } else {
                current.update(value.labelEn(), value.labelAr(), value.displayOrder(), value.active());
            }
        }
        existing.values().stream().filter(value -> !proposedCodes.contains(value.getCode()))
                .forEach(value -> value.update(value.getLabelEn(), value.getLabelAr(), value.getDisplayOrder(), false));
        // values are part of the list: concurrent changes to it must see a new version
        entityManager.lock(lov, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        support.auditApplied("LOV_UPDATED", SUBJECT, lov.getId(), change, Map.of("code", lov.getCode()));
        return ApplyResult.ok();
    }

    /** A LOV used by fields must keep an active value, and every field default must stay an active value. */
    private List<Violation> usageViolations(Lov lov, LovPayload payload) {
        List<Violation> violations = new ArrayList<>();
        Set<String> active = payload.values() == null ? Set.of() : payload.values().stream()
                .filter(LovValuePayload::active).map(LovValuePayload::code).collect(Collectors.toSet());
        List<FieldDefinition> users = fields.findByLovCode(lov.getCode());
        if (!users.isEmpty() && active.isEmpty()) {
            violations.add(new Violation("values", "LOV_NO_ACTIVE_VALUE",
                    "The list is used by fields and needs at least one active value."));
        }
        users.stream().map(FieldDefinition::spec).filter(spec -> spec.defaultValue() != null
                && !active.contains(spec.defaultValue())).forEach(spec -> violations.add(new Violation("values",
                "LOV_DEFAULT_IN_USE", "Value " + spec.defaultValue() + " is the default of field " + spec.key()
                        + " and must stay active.")));
        return violations;
    }

    private static List<Violation> contentViolations(LovPayload payload) {
        List<Violation> violations = new ArrayList<>();
        MetadataChangeSupport.checkName("name", payload.name(), 200, violations);
        if (payload.values() == null || payload.values().isEmpty()) {
            violations.add(new Violation("values", "LOV_VALUES_REQUIRED", "At least one value is required."));
            return violations;
        }
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < payload.values().size(); i++) {
            LovValuePayload value = payload.values().get(i);
            String path = "values[" + i + "]";
            if (value.code() == null || !VALUE_CODE.matcher(value.code()).matches()) {
                violations.add(new Violation(path + ".code", "LOV_VALUE_CODE_INVALID",
                        "Code must be 1-50 of A-Z, 0-9 and _."));
            } else if (!codes.add(value.code())) {
                violations.add(new Violation(path + ".code", "LOV_VALUE_CODE_DUPLICATE", "Code is used twice."));
            }
            if (value.labelEn() == null || value.labelEn().isBlank()) {
                violations.add(new Violation(path + ".label", "LOV_VALUE_LABEL_INVALID",
                        "An English label is required."));
            }
        }
        return violations;
    }

    private static LovPayload normalise(String code, LovPayload payload) {
        return new LovPayload(code, payload.name().trim(), payload.values().stream()
                .map(value -> new LovValuePayload(value.code(), value.labelEn().trim(),
                        MetadataChangeSupport.optional(value.labelAr()), value.displayOrder(), value.active()))
                .toList());
    }

    private List<LovValuePayload> valuesOf(UUID lovId) {
        return values.findByLovId(lovId).stream()
                .sorted(Comparator.comparingInt(LovValue::getDisplayOrder).thenComparing(LovValue::getCode))
                .map(value -> new LovValuePayload(value.getCode(), value.getLabelEn(), value.getLabelAr(),
                        value.getDisplayOrder(), value.isActive()))
                .toList();
    }

    private Lov load(UUID id) {
        return lovs.findById(id).orElseThrow(() -> ApiException.notFound(LOV_NOT_FOUND, "List of values not found."));
    }

    private Lov loadAtVersion(UUID id, long expectedVersion) {
        Lov lov = load(id);
        if (lov.getVersionLock() != expectedVersion) {
            throw ApiException.staleVersion();
        }
        return lov;
    }
}
