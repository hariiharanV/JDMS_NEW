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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cabinets: reads, proposals (maker) and applying approved changes (checker). */
@Service
public class CabinetAdministration implements ChangeHandler {

    public static final String SUBJECT = "CABINET";
    public static final String CABINET_NOT_FOUND = "CABINET_NOT_FOUND";

    private final CabinetRepository cabinets;
    private final ChangeRequests changeRequests;
    private final MetadataChangeSupport support;

    CabinetAdministration(CabinetRepository cabinets, ChangeRequests changeRequests, MetadataChangeSupport support) {
        this.cabinets = cabinets;
        this.changeRequests = changeRequests;
        this.support = support;
    }

    /** Proposed cabinet values. {@code status} is ignored on create (new cabinets are active). */
    public record CabinetPayload(String code, String name, Integer maxFileSizeMb, Long quotaMb,
            String defaultProcessKey, MetadataStatus status) {
    }

    public record CabinetView(UUID id, String code, String name, int maxFileSizeMb, long quotaMb,
            String defaultProcessKey, MetadataStatus status, long version) {

        static CabinetView of(Cabinet cabinet) {
            return new CabinetView(cabinet.getId(), cabinet.getCode(), cabinet.getName(), cabinet.getMaxFileSizeMb(),
                    cabinet.getQuotaMb(), cabinet.getDefaultProcessKey(), cabinet.getStatus(), cabinet.getVersionLock());
        }
    }

    @Transactional(readOnly = true)
    public Page<CabinetView> list(Pageable pageable) {
        return cabinets.findAll(pageable).map(CabinetView::of);
    }

    @Transactional(readOnly = true)
    public CabinetView get(UUID id) {
        return CabinetView.of(load(id));
    }

    @Transactional
    public PendingChangeView proposeCreate(CabinetPayload payload) {
        List<Violation> violations = violations(payload, true);
        ValidationFailedException.throwIfAny("The cabinet is invalid.", violations);
        if (cabinets.existsByCode(payload.code())) {
            throw MetadataChangeSupport.codeInUse("cabinet");
        }
        CabinetPayload normalised = new CabinetPayload(payload.code(), payload.name().trim(), payload.maxFileSizeMb(),
                payload.quotaMb(), payload.defaultProcessKey(), MetadataStatus.ACTIVE);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.CREATE, Ids.newId(),
                payload.code(), null, normalised, "Create cabinet " + payload.code()));
    }

    @Transactional
    public PendingChangeView proposeUpdate(UUID id, long expectedVersion, CabinetPayload payload) {
        Cabinet cabinet = loadAtVersion(id, expectedVersion);
        if (payload.code() != null && !payload.code().equals(cabinet.getCode())) {
            throw new ValidationFailedException("The cabinet is invalid.",
                    List.of(new Violation("code", "CODE_IMMUTABLE", "The code cannot be changed.")));
        }
        ValidationFailedException.throwIfAny("The cabinet is invalid.", violations(payload, false));
        CabinetPayload normalised = new CabinetPayload(cabinet.getCode(), payload.name().trim(),
                payload.maxFileSizeMb(), payload.quotaMb(), payload.defaultProcessKey(), payload.status());
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE, id, null, expectedVersion,
                normalised, "Update cabinet " + cabinet.getCode()));
    }

    @Transactional
    public PendingChangeView proposeDeactivate(UUID id, long expectedVersion) {
        Cabinet cabinet = loadAtVersion(id, expectedVersion);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.DEACTIVATE, id, null,
                expectedVersion, Map.of(), "Deactivate cabinet " + cabinet.getCode()));
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
        return switch (change.operation()) {
            case CREATE -> applyCreate(change);
            case UPDATE -> applyUpdate(change);
            case DEACTIVATE -> applyDeactivate(change);
            case DELETE -> throw new IllegalStateException("Cabinets are never deleted");
        };
    }

    private ApplyResult applyCreate(PendingChangeView change) {
        CabinetPayload payload = support.payload(change, CabinetPayload.class);
        if (cabinets.existsByCode(payload.code())) {
            return ApplyResult.stale("A cabinet with code " + payload.code() + " was created in the meantime.");
        }
        Cabinet cabinet = cabinets.save(new Cabinet(change.subjectId(), payload.code(), payload.name(),
                payload.maxFileSizeMb(), payload.quotaMb(), payload.defaultProcessKey()));
        support.auditApplied("CABINET_CREATED", SUBJECT, cabinet.getId(), change, Map.of("code", payload.code()));
        return ApplyResult.ok();
    }

    private ApplyResult applyUpdate(PendingChangeView change) {
        Cabinet cabinet = cabinets.findById(change.subjectId()).orElse(null);
        if (cabinet == null || cabinet.getVersionLock() != change.baseVersion()) {
            return ApplyResult.stale("The cabinet was changed after this change was proposed.");
        }
        CabinetPayload payload = support.payload(change, CabinetPayload.class);
        cabinet.update(payload.name(), payload.maxFileSizeMb(), payload.quotaMb(), payload.defaultProcessKey(),
                payload.status());
        support.auditApplied("CABINET_UPDATED", SUBJECT, cabinet.getId(), change, Map.of("code", cabinet.getCode()));
        return ApplyResult.ok();
    }

    private ApplyResult applyDeactivate(PendingChangeView change) {
        Cabinet cabinet = cabinets.findById(change.subjectId()).orElse(null);
        if (cabinet == null || cabinet.getVersionLock() != change.baseVersion()) {
            return ApplyResult.stale("The cabinet was changed after this change was proposed.");
        }
        cabinet.deactivate();
        support.auditApplied("CABINET_DEACTIVATED", SUBJECT, cabinet.getId(), change,
                Map.of("code", cabinet.getCode()));
        return ApplyResult.ok();
    }

    private Cabinet load(UUID id) {
        return cabinets.findById(id).orElseThrow(() -> ApiException.notFound(CABINET_NOT_FOUND, "Cabinet not found."));
    }

    private Cabinet loadAtVersion(UUID id, long expectedVersion) {
        Cabinet cabinet = load(id);
        if (cabinet.getVersionLock() != expectedVersion) {
            throw ApiException.staleVersion();
        }
        return cabinet;
    }

    private static List<Violation> violations(CabinetPayload payload, boolean create) {
        List<Violation> violations = new ArrayList<>();
        if (create) {
            MetadataChangeSupport.checkCode(payload.code(), violations);
        } else if (payload.status() == null) {
            violations.add(new Violation("status", "STATUS_REQUIRED", "Status is required."));
        }
        MetadataChangeSupport.checkName("name", payload.name(), 200, violations);
        if (payload.maxFileSizeMb() == null || payload.maxFileSizeMb() < 1 || payload.maxFileSizeMb() > 500) {
            violations.add(new Violation("maxFileSizeMb", "FILE_SIZE_INVALID", "Must be 1 to 500 MB."));
        } else if (payload.quotaMb() == null || payload.quotaMb() < payload.maxFileSizeMb()) {
            violations.add(new Violation("quotaMb", "QUOTA_INVALID", "Must be at least the maximum file size."));
        }
        MetadataChangeSupport.checkProcessKey("defaultProcessKey", payload.defaultProcessKey(), violations);
        return violations;
    }
}
