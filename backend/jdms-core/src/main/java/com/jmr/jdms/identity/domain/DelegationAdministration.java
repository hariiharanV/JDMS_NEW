package com.jmr.jdms.identity.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.context.RequestContext;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delegation of roles (spec §6): time-bound, never more than the delegator holds in their own right, no
 * re-delegation. A user proposes delegating their own roles; a security admin approves (neither the delegator nor the
 * delegate can); either party or a security admin can revoke at once.
 */
@Service
public class DelegationAdministration implements ChangeHandler {

    public static final String SUBJECT = "DELEGATION";
    public static final String DELEGATION_NOT_FOUND = "DELEGATION_NOT_FOUND";
    static final Duration MAX_LENGTH = Duration.ofDays(90);
    private static final Set<String> NOT_DELEGABLE = Set.of(Roles.INTEGRATION_CLIENT);

    private final DelegationRepository delegations;
    private final UserProfileRepository profiles;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final ChangeRequests changeRequests;
    private final AccessPolicy policy;
    private final AuditTrail auditTrail;
    private final JsonMapper jsonMapper;

    DelegationAdministration(DelegationRepository delegations, UserProfileRepository profiles, RoleRepository roles,
            UserRoleRepository userRoles, ChangeRequests changeRequests, AccessPolicy policy, AuditTrail auditTrail,
            JsonMapper jsonMapper) {
        this.delegations = delegations;
        this.profiles = profiles;
        this.roles = roles;
        this.userRoles = userRoles;
        this.changeRequests = changeRequests;
        this.policy = policy;
        this.auditTrail = auditTrail;
        this.jsonMapper = jsonMapper;
    }

    /** {@code roleCode} null delegates all the delegator's delegable roles. */
    public record DelegationInput(UUID toUserId, String roleCode, Instant validFrom, Instant validTo, String reason) {
    }

    public record DelegationPayload(UUID fromUserId, UUID toUserId, String roleCode, Instant validFrom,
            Instant validTo, String reason) {
    }

    public record DelegationView(UUID id, UUID fromUserId, UUID toUserId, String roleCode, Instant validFrom,
            Instant validTo, String reason, UUID approvedBy, boolean active) {
    }

    static boolean isDelegable(String role) {
        return !NOT_DELEGABLE.contains(role);
    }

    @Transactional(readOnly = true)
    public List<DelegationView> list() {
        RequestContext caller = RequestContext.require();
        List<Delegation> visible = isSecurityAdmin() ? delegations.findAll()
                : delegations.findByFromUserIdOrToUserId(caller.subjectId(), caller.subjectId());
        return visible.stream().map(this::view).toList();
    }

    @Transactional
    public PendingChangeView propose(DelegationInput input) {
        RequestContext caller = RequestContext.require();
        Instant now = Instant.now();
        Instant validFrom = micros(input.validFrom() == null ? now : input.validFrom());
        Instant validTo = micros(input.validTo());
        List<Violation> violations = new ArrayList<>();
        if (input.toUserId() == null || input.toUserId().equals(caller.subjectId())
                || profiles.findById(input.toUserId()).isEmpty()) {
            violations.add(new Violation("toUserId", "DELEGATE_INVALID", "Delegate to another existing user."));
        }
        Set<String> own = caller.ownRoles().stream().filter(DelegationAdministration::isDelegable)
                .collect(Collectors.toSet());
        if (input.roleCode() != null ? !own.contains(input.roleCode()) : own.isEmpty()) {
            violations.add(new Violation("roleCode", "ROLE_NOT_HELD",
                    "You can only delegate roles you hold yourself (delegated roles cannot be passed on)."));
        }
        if (validTo == null || !validTo.isAfter(validFrom) || !validTo.isAfter(now)) {
            violations.add(new Violation("validTo", "VALIDITY_INVALID", "validTo must be in the future and after validFrom."));
        } else if (Duration.between(validFrom, validTo).compareTo(MAX_LENGTH) > 0) {
            violations.add(new Violation("validTo", "DELEGATION_TOO_LONG", "A delegation lasts at most 90 days."));
        }
        if (input.reason() == null || input.reason().isBlank() || input.reason().length() > 500) {
            violations.add(new Violation("reason", "REASON_REQUIRED", "A reason (at most 500 characters) is required."));
        }
        ValidationFailedException.throwIfAny("The delegation is invalid.", violations);
        DelegationPayload payload = new DelegationPayload(caller.subjectId(), input.toUserId(), input.roleCode(),
                validFrom, validTo, input.reason().trim());
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.CREATE, Ids.newId(), null, null,
                payload, "Delegate " + (input.roleCode() == null ? "all roles" : input.roleCode()) + " of "
                        + caller.username() + " until " + validTo));
    }

    @Transactional
    public DelegationView revoke(UUID id) {
        RequestContext caller = RequestContext.require();
        Delegation delegation = delegations.findById(id)
                .filter(found -> isSecurityAdmin() || caller.subjectId().equals(found.getFromUserId())
                        || caller.subjectId().equals(found.getToUserId()))
                .orElseThrow(() -> ApiException.notFound(DELEGATION_NOT_FOUND, "Delegation not found."));
        delegation.revoke(micros(Instant.now()));
        delegations.flush();
        auditTrail.record(AuditEntry.success("DELEGATION_REVOKED", "DELEGATION", id,
                Map.of("toUserId", delegation.getToUserId().toString())));
        return view(delegation);
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Set<String> candidateRoles() {
        return Set.of(Roles.SECURITY_ADMIN);
    }

    @Override
    public boolean canDecide(PendingChangeView change) {
        return isSecurityAdmin();
    }

    @Override
    public Optional<String> approvalBlocker(PendingChangeView change, RequestContext approver) {
        DelegationPayload payload = jsonMapper.treeToValue(change.payload(), DelegationPayload.class);
        return approver.subjectId().equals(payload.fromUserId()) || approver.subjectId().equals(payload.toUserId())
                ? Optional.of("Neither the delegator nor the delegate can approve a delegation.") : Optional.empty();
    }

    @Override
    public ApplyResult apply(PendingChangeView change) {
        DelegationPayload payload = jsonMapper.treeToValue(change.payload(), DelegationPayload.class);
        Set<String> delegatorRoles = roles.findAllById(userRoles.findByUserId(payload.fromUserId()).stream()
                .map(UserRole::getRoleId).toList()).stream().map(Role::getCode).collect(Collectors.toSet());
        UUID roleId = null;
        if (payload.roleCode() != null) {
            if (!delegatorRoles.contains(payload.roleCode())) {
                return ApplyResult.stale("The delegator no longer holds " + payload.roleCode() + ".");
            }
            roleId = roles.findByCode(payload.roleCode()).map(Role::getId).orElse(null);
        }
        if (!payload.validTo().isAfter(Instant.now())) {
            return ApplyResult.stale("The delegation period has already ended.");
        }
        Delegation delegation = delegations.save(new Delegation(payload.fromUserId(), payload.toUserId(), roleId,
                payload.validFrom(), payload.validTo(), payload.reason()));
        delegation.approve(RequestContext.require().subjectId());
        delegations.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changeId", change.id().toString());
        details.put("fromUserId", payload.fromUserId().toString());
        details.put("toUserId", payload.toUserId().toString());
        details.put("role", payload.roleCode() == null ? "ALL" : payload.roleCode());
        details.put("validTo", payload.validTo().toString());
        auditTrail.record(AuditEntry.success("DELEGATION_CREATED", "DELEGATION", delegation.getId(), details));
        return ApplyResult.ok();
    }

    private boolean isSecurityAdmin() {
        Subject subject = Subject.current();
        return policy.decide(subject, Action.ADMIN_ACCESS, new ResourceRef.Tenant(subject.tenantId())).allowed();
    }

    private DelegationView view(Delegation delegation) {
        String role = delegation.getRoleId() == null ? null
                : roles.findById(delegation.getRoleId()).map(Role::getCode).orElse(null);
        return new DelegationView(delegation.getId(), delegation.getFromUserId(), delegation.getToUserId(), role,
                delegation.getValidFrom(), delegation.getValidTo(), delegation.getReason(),
                delegation.getApprovedBy(), delegation.activeAt(Instant.now()));
    }

    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
