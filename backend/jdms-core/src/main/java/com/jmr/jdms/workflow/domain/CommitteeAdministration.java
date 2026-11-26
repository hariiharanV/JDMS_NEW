package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.security.Roles;
import com.jmr.jdms.workflow.api.ApplyResult;
import com.jmr.jdms.workflow.api.ApproverDirectory;
import com.jmr.jdms.workflow.api.ChangeHandler;
import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.ChangeProposal;
import com.jmr.jdms.workflow.api.ChangeRequests;
import com.jmr.jdms.workflow.api.PendingChangeView;
import com.jmr.jdms.workflow.api.TenantPermissions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Committees (spec §4.4, §8 admin): reads, proposals and applying approved changes (maker-checker, ADR-0009). */
@Service
public class CommitteeAdministration implements ChangeHandler {

    public static final String SUBJECT = "COMMITTEE";
    public static final String COMMITTEE_NOT_FOUND = "COMMITTEE_NOT_FOUND";
    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");
    static final int MAX_MEMBERS = 50;

    private final CommitteeRepository committees;
    private final CommitteeMemberRepository members;
    private final ChangeRequests changeRequests;
    private final ApproverDirectory directory;
    private final TenantPermissions permissions;
    private final AuditTrail auditTrail;
    private final JsonMapper jsonMapper;
    private final EntityManager entityManager;

    CommitteeAdministration(CommitteeRepository committees, CommitteeMemberRepository members,
            ChangeRequests changeRequests, ApproverDirectory directory, TenantPermissions permissions,
            AuditTrail auditTrail, JsonMapper jsonMapper, EntityManager entityManager) {
        this.committees = committees;
        this.members = members;
        this.changeRequests = changeRequests;
        this.directory = directory;
        this.permissions = permissions;
        this.auditTrail = auditTrail;
        this.jsonMapper = jsonMapper;
        this.entityManager = entityManager;
    }

    public record MemberInput(UUID userId, String designation) {
    }

    /** Proposed values; {@code code} only on create, {@code status} only on update. */
    public record CommitteePayload(String code, String name, Integer minQuorum, List<MemberInput> members,
            CommitteeStatus status) {
    }

    public record CommitteeView(UUID id, String code, String name, int minQuorum, CommitteeStatus status,
            List<MemberInput> members, long version) {
    }

    @Transactional(readOnly = true)
    public Page<CommitteeView> list(Pageable pageable) {
        return committees.findAll(pageable).map(this::view);
    }

    @Transactional(readOnly = true)
    public CommitteeView get(UUID id) {
        return view(load(id));
    }

    @Transactional
    public PendingChangeView proposeCreate(CommitteePayload payload) {
        ValidationFailedException.throwIfAny("The committee is invalid.", violations(payload, true));
        if (committees.existsByCode(payload.code())) {
            throw new ApiException(HttpStatus.CONFLICT, "CODE_IN_USE", "A committee with this code already exists.");
        }
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.CREATE, Ids.newId(), payload.code(),
                null, normalise(payload, payload.code(), CommitteeStatus.ACTIVE),
                "Create committee " + payload.code()));
    }

    @Transactional
    public PendingChangeView proposeUpdate(UUID id, long expectedVersion, CommitteePayload payload) {
        Committee committee = loadAtVersion(id, expectedVersion);
        if (payload.code() != null && !payload.code().equals(committee.getCode())) {
            throw new ValidationFailedException("The committee is invalid.",
                    List.of(new Violation("code", "CODE_IMMUTABLE", "The code cannot be changed.")));
        }
        ValidationFailedException.throwIfAny("The committee is invalid.", violations(payload, false));
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE, id, null, expectedVersion,
                normalise(payload, committee.getCode(), payload.status()), "Update committee " + committee.getCode()));
    }

    @Transactional
    public PendingChangeView proposeDeactivate(UUID id, long expectedVersion) {
        Committee committee = loadAtVersion(id, expectedVersion);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.DEACTIVATE, id, null,
                expectedVersion, Map.of(), "Deactivate committee " + committee.getCode()));
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Set<String> candidateRoles() {
        return Set.of(Roles.TENANT_ADMIN);
    }

    /** Committees are workflow configuration: tenant admins ({@code ADMIN_METADATA}) decide changes to them. */
    @Override
    public boolean canDecide(PendingChangeView change) {
        return permissions.allowed("ADMIN_METADATA");
    }

    @Override
    public ApplyResult apply(PendingChangeView change) {
        return switch (change.operation()) {
            case CREATE -> applyCreate(change);
            case UPDATE -> applyUpdate(change);
            case DEACTIVATE -> applyDeactivate(change);
            case DELETE -> throw new IllegalStateException("Committees are never deleted");
        };
    }

    private ApplyResult applyCreate(PendingChangeView change) {
        CommitteePayload payload = jsonMapper.treeToValue(change.payload(), CommitteePayload.class);
        if (committees.existsByCode(payload.code())) {
            return ApplyResult.stale("A committee with code " + payload.code() + " was created in the meantime.");
        }
        if (!allMembersActive(payload)) {
            return ApplyResult.stale("A proposed member is no longer an active user.");
        }
        Committee committee = committees.save(new Committee(change.subjectId(), payload.code(), payload.name(),
                payload.minQuorum()));
        saveMembers(committee.getId(), payload.members());
        auditApplied("COMMITTEE_CREATED", committee, change);
        return ApplyResult.ok();
    }

    private ApplyResult applyUpdate(PendingChangeView change) {
        Committee committee = committees.findById(change.subjectId()).orElse(null);
        if (committee == null || committee.getVersionLock() != change.baseVersion()) {
            return ApplyResult.stale("The committee was changed after this change was proposed.");
        }
        CommitteePayload payload = jsonMapper.treeToValue(change.payload(), CommitteePayload.class);
        if (!allMembersActive(payload)) {
            return ApplyResult.stale("A proposed member is no longer an active user.");
        }
        committee.update(payload.name(), payload.minQuorum(), payload.status());
        // membership is part of the committee: a changed member list is a new committee version
        entityManager.lock(committee, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        members.deleteByCommitteeId(committee.getId());
        members.flush();
        saveMembers(committee.getId(), payload.members());
        auditApplied("COMMITTEE_UPDATED", committee, change);
        return ApplyResult.ok();
    }

    private ApplyResult applyDeactivate(PendingChangeView change) {
        Committee committee = committees.findById(change.subjectId()).orElse(null);
        if (committee == null || committee.getVersionLock() != change.baseVersion()) {
            return ApplyResult.stale("The committee was changed after this change was proposed.");
        }
        committee.deactivate();
        auditApplied("COMMITTEE_DEACTIVATED", committee, change);
        return ApplyResult.ok();
    }

    private void saveMembers(UUID committeeId, List<MemberInput> inputs) {
        inputs.forEach(input -> members.save(new CommitteeMember(committeeId, input.userId(), input.designation())));
    }

    private boolean allMembersActive(CommitteePayload payload) {
        return payload.members().stream().allMatch(member -> directory.isActiveUser(member.userId()));
    }

    private CommitteePayload normalise(CommitteePayload payload, String code, CommitteeStatus status) {
        List<MemberInput> normalised = payload.members().stream().map(member -> new MemberInput(member.userId(),
                member.designation() == null || member.designation().isBlank() ? null : member.designation().trim()))
                .toList();
        return new CommitteePayload(code, payload.name().trim(), payload.minQuorum(), normalised, status);
    }

    private List<Violation> violations(CommitteePayload payload, boolean create) {
        List<Violation> violations = new ArrayList<>();
        if (create && (payload.code() == null || !CODE.matcher(payload.code()).matches())) {
            violations.add(new Violation("code", "CODE_INVALID",
                    "Code must be 2-50 of A-Z, 0-9 and _, starting with a letter."));
        }
        if (!create && payload.status() == null) {
            violations.add(new Violation("status", "STATUS_REQUIRED", "Status is required."));
        }
        if (payload.name() == null || payload.name().isBlank() || payload.name().length() > 200) {
            violations.add(new Violation("name", "NAME_INVALID", "Required, at most 200 characters."));
        }
        List<MemberInput> inputs = payload.members() == null ? List.of() : payload.members();
        if (inputs.isEmpty() || inputs.size() > MAX_MEMBERS) {
            violations.add(new Violation("members", "MEMBERS_INVALID", "1 to " + MAX_MEMBERS + " members."));
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            MemberInput member = inputs.get(i);
            if (member == null || member.userId() == null || !directory.isActiveUser(member.userId())) {
                violations.add(new Violation("members[" + i + "].userId", "USER_UNKNOWN",
                        "Must be an active user of this tenant."));
            } else if (!seen.add(member.userId())) {
                violations.add(new Violation("members[" + i + "].userId", "MEMBER_DUPLICATE", "Listed twice."));
            }
            if (member != null && member.designation() != null && member.designation().length() > 100) {
                violations.add(new Violation("members[" + i + "].designation", "DESIGNATION_INVALID",
                        "At most 100 characters."));
            }
        }
        if (payload.minQuorum() == null || payload.minQuorum() < 1 || payload.minQuorum() > inputs.size()) {
            violations.add(new Violation("minQuorum", "QUORUM_INVALID", "Must be 1 to the number of members."));
        }
        return violations;
    }

    private void auditApplied(String action, Committee committee, PendingChangeView change) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changeId", change.id().toString());
        details.put("proposedBy", change.proposedBy().toString());
        details.put("code", committee.getCode());
        auditTrail.record(AuditEntry.success(action, SUBJECT, committee.getId(), details));
    }

    private CommitteeView view(Committee committee) {
        return new CommitteeView(committee.getId(), committee.getCode(), committee.getName(),
                committee.getMinQuorum(), committee.getStatus(),
                members.findByCommitteeIdOrderByCreatedAtAscIdAsc(committee.getId()).stream()
                        .map(member -> new MemberInput(member.getUserId(), member.getDesignation())).toList(),
                committee.getVersionLock());
    }

    private Committee load(UUID id) {
        return committees.findById(id)
                .orElseThrow(() -> ApiException.notFound(COMMITTEE_NOT_FOUND, "Committee not found."));
    }

    private Committee loadAtVersion(UUID id, long expectedVersion) {
        Committee committee = load(id);
        if (committee.getVersionLock() != expectedVersion) {
            throw ApiException.staleVersion();
        }
        return committee;
    }
}
