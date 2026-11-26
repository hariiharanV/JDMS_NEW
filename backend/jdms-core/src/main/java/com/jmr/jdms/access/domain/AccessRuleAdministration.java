package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.ScopeResolver;
import com.jmr.jdms.access.api.ScopeType;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.AccessRulesChanged;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.security.Roles;
import com.jmr.jdms.tenant.api.BranchDirectory;
import com.jmr.jdms.tenant.api.BranchInfo;
import com.jmr.jdms.workflow.api.ApplyResult;
import com.jmr.jdms.workflow.api.ChangeHandler;
import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.ChangeProposal;
import com.jmr.jdms.workflow.api.ChangeRequests;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access rule administration with maker-checker (spec §6 rules: every change needs a second security admin; nobody
 * approves a change to their own permissions). The last tenant-wide rule granting {@code ADMIN_ACCESS} to a role
 * cannot be removed, so a tenant cannot lock itself out.
 */
@Service
public class AccessRuleAdministration implements ChangeHandler {

    public static final String SUBJECT = "ACCESS_RULE";
    public static final String RULE_NOT_FOUND = "ACCESS_RULE_NOT_FOUND";
    public static final String LAST_ADMIN_RULE = "LAST_ADMIN_ACCESS_RULE";

    private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");

    private final AccessRuleRepository rules;
    private final ObjectProvider<ScopeResolver> scopeResolvers;
    private final BranchDirectory branches;
    private final ChangeRequests changeRequests;
    private final AccessPolicy policy;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final JsonMapper jsonMapper;

    AccessRuleAdministration(AccessRuleRepository rules, ObjectProvider<ScopeResolver> scopeResolvers,
            BranchDirectory branches, ChangeRequests changeRequests, AccessPolicy policy, AuditTrail auditTrail,
            ApplicationEventPublisher events, JsonMapper jsonMapper) {
        this.rules = rules;
        this.scopeResolvers = scopeResolvers;
        this.branches = branches;
        this.changeRequests = changeRequests;
        this.policy = policy;
        this.auditTrail = auditTrail;
        this.events = events;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public Page<RuleView> list(Pageable pageable) {
        return rules.findAll(pageable).map(this::view);
    }

    @Transactional(readOnly = true)
    public RuleView get(UUID id) {
        return view(load(id));
    }

    @Transactional
    public PendingChangeView proposeCreate(RuleInput input) {
        RuleSpec spec = resolve(input);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.CREATE, Ids.newId(), null, null,
                spec, "Create access rule: " + summary(spec)));
    }

    @Transactional
    public PendingChangeView proposeUpdate(UUID id, long expectedVersion, RuleInput input) {
        AccessRule rule = loadAtVersion(id, expectedVersion);
        RuleSpec spec = resolve(input);
        if (!keepsAdminAccess(id, spec)) {
            throw lockout();
        }
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE, id, null, expectedVersion,
                new RuleChange(rule.spec(), spec), "Update access rule: " + summary(spec)));
    }

    @Transactional
    public PendingChangeView proposeDelete(UUID id, long expectedVersion) {
        AccessRule rule = loadAtVersion(id, expectedVersion);
        if (!keepsAdminAccess(id, null)) {
            throw lockout();
        }
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.DELETE, id, null, expectedVersion,
                new RuleChange(rule.spec(), null), "Delete access rule: " + summary(rule.spec())));
    }

    /** Payload of updates and deletes: the rule before and after (after is null for deletes). */
    public record RuleChange(RuleSpec before, RuleSpec after) {
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
    public String historyType() {
        return "ACCESS_RULE";
    }

    @Override
    public boolean canDecide(PendingChangeView change) {
        Subject subject = Subject.current();
        return policy.decide(subject, Action.ADMIN_ACCESS, new ResourceRef.Tenant(subject.tenantId())).allowed();
    }

    /** Nobody approves a rule that applies to themselves, before or after the change. */
    @Override
    public Optional<String> approvalBlocker(PendingChangeView change, RequestContext approver) {
        List<RuleSpec> touched = new ArrayList<>();
        if (change.operation() == ChangeOperation.CREATE) {
            touched.add(jsonMapper.treeToValue(change.payload(), RuleSpec.class));
        } else {
            RuleChange ruleChange = jsonMapper.treeToValue(change.payload(), RuleChange.class);
            touched.add(ruleChange.before());
            if (ruleChange.after() != null) {
                touched.add(ruleChange.after());
            }
        }
        boolean own = touched.stream().anyMatch(spec -> switch (spec.principalType()) {
            case USER -> approver.subjectId().toString().equals(spec.principalId());
            case ROLE -> approver.roles().contains(spec.principalId());
        });
        return own ? Optional.of("You cannot approve a change to rules that apply to yourself.") : Optional.empty();
    }

    @Override
    public ApplyResult apply(PendingChangeView change) {
        return switch (change.operation()) {
            case CREATE -> {
                RuleSpec spec = jsonMapper.treeToValue(change.payload(), RuleSpec.class);
                if (scopeMissing(spec)) {
                    yield ApplyResult.stale("The rule's scope no longer exists.");
                }
                AccessRule rule = rules.save(new AccessRule(change.subjectId(), spec));
                applied("ACCESS_RULE_CREATED", rule.getId(), change, spec);
                yield ApplyResult.ok();
            }
            case UPDATE -> {
                AccessRule rule = rules.findById(change.subjectId()).orElse(null);
                if (rule == null || rule.getVersionLock() != change.baseVersion()) {
                    yield ApplyResult.stale("The rule was changed after this change was proposed.");
                }
                RuleSpec spec = jsonMapper.treeToValue(change.payload(), RuleChange.class).after();
                if (scopeMissing(spec) || !keepsAdminAccess(rule.getId(), spec)) {
                    yield ApplyResult.stale("The change is no longer valid (scope or last admin rule).");
                }
                rule.update(spec);
                applied("ACCESS_RULE_UPDATED", rule.getId(), change, spec);
                yield ApplyResult.ok();
            }
            case DELETE -> {
                AccessRule rule = rules.findById(change.subjectId()).orElse(null);
                if (rule == null || rule.getVersionLock() != change.baseVersion()) {
                    yield ApplyResult.stale("The rule was changed after this change was proposed.");
                }
                if (!keepsAdminAccess(rule.getId(), null)) {
                    yield ApplyResult.stale("It is now the last rule granting ADMIN_ACCESS.");
                }
                RuleSpec spec = rule.spec();
                rules.delete(rule);
                applied("ACCESS_RULE_DELETED", rule.getId(), change, spec);
                yield ApplyResult.ok();
            }
            case DEACTIVATE -> throw new IllegalStateException("Rules are deleted, not deactivated");
        };
    }

    private void applied(String action, UUID ruleId, PendingChangeView change, RuleSpec spec) {
        rules.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changeId", change.id().toString());
        details.put("proposedBy", change.proposedBy().toString());
        details.put("rule", summary(spec));
        auditTrail.record(AuditEntry.success(action, SUBJECT, ruleId, details));
        events.publishEvent(new AccessRulesChanged(RequestContext.require().tenantId()));
    }

    private RuleSpec resolve(RuleInput input) {
        List<Violation> violations = new ArrayList<>();
        UUID tenantId = RequestContext.require().tenantId();
        ScopeType scopeType = input.scopeType();
        UUID scopeId = input.scopeId();
        if (scopeType == null) {
            violations.add(new Violation("scopeType", "SCOPE_REQUIRED", "Scope type is required."));
        } else if (scopeType == ScopeType.TENANT) {
            if (scopeId != null && !scopeId.equals(tenantId)) {
                violations.add(new Violation("scopeId", "SCOPE_UNKNOWN", "A tenant rule applies to your tenant."));
            }
            scopeId = tenantId;
        } else if (scopeId == null || describe(scopeType, scopeId).isEmpty()) {
            violations.add(new Violation("scopeId", "SCOPE_UNKNOWN", "The scope does not exist."));
        }
        if (input.principalType() == null) {
            violations.add(new Violation("principalType", "PRINCIPAL_REQUIRED", "Principal type is required."));
        } else if (input.principalType() == PrincipalKind.ROLE
                && (input.principalId() == null || !ROLE.matcher(input.principalId()).matches())) {
            violations.add(new Violation("principalId", "PRINCIPAL_INVALID", "A role code is required."));
        } else if (input.principalType() == PrincipalKind.USER && !isUuid(input.principalId())) {
            violations.add(new Violation("principalId", "PRINCIPAL_INVALID", "A user ID is required."));
        }
        if (input.actions() == null || input.actions().isEmpty()) {
            violations.add(new Violation("actions", "ACTIONS_REQUIRED", "At least one action is required."));
        }
        if (input.effect() == null) {
            violations.add(new Violation("effect", "EFFECT_REQUIRED", "Effect (ALLOW or DENY) is required."));
        }
        BranchConstraint constraint = input.branchConstraint() == null ? BranchConstraint.OWN
                : input.branchConstraint();
        Set<UUID> branchIds = new HashSet<>();
        if (constraint == BranchConstraint.LIST) {
            List<String> codes = input.branchCodes() == null ? List.of() : input.branchCodes();
            List<BranchInfo> found = branches.findByCodes(codes);
            if (codes.isEmpty() || found.size() < new HashSet<>(codes).size()) {
                violations.add(new Violation("branchCodes", "BRANCH_UNKNOWN",
                        "LIST rules need existing branch codes."));
            }
            found.forEach(branch -> branchIds.add(branch.id()));
        }
        if (input.validFrom() != null && input.validTo() != null && !input.validTo().isAfter(input.validFrom())) {
            violations.add(new Violation("validTo", "VALIDITY_INVALID", "validTo must be after validFrom."));
        }
        if (input.description() != null && input.description().length() > 500) {
            violations.add(new Violation("description", "DESCRIPTION_TOO_LONG", "At most 500 characters."));
        }
        ValidationFailedException.throwIfAny("The access rule is invalid.", violations);
        return new RuleSpec(scopeType, scopeId, input.principalType(), input.principalId(), input.actions(),
                input.effect(), constraint, branchIds, micros(input.validFrom()), micros(input.validTo()),
                input.description());
    }

    /** Whether, after replacing (or deleting, when {@code replacement} is null) the rule, an admin rule remains. */
    private boolean keepsAdminAccess(UUID ruleId, RuleSpec replacement) {
        List<RuleSpec> after = new ArrayList<>(rules.findAll().stream().filter(rule -> !rule.getId().equals(ruleId))
                .map(AccessRule::spec).toList());
        if (replacement != null) {
            after.add(replacement);
        }
        return after.stream().anyMatch(spec -> spec.scopeType() == ScopeType.TENANT && spec.effect() == Effect.ALLOW
                && spec.principalType() == PrincipalKind.ROLE && spec.actions().contains(Action.ADMIN_ACCESS)
                && spec.validTo() == null);
    }

    private boolean scopeMissing(RuleSpec spec) {
        return spec.scopeType() != ScopeType.TENANT && describe(spec.scopeType(), spec.scopeId()).isEmpty();
    }

    private Optional<String> describe(ScopeType type, UUID id) {
        if (type == ScopeType.TENANT) {
            return Optional.of("Tenant");
        }
        return scopeResolvers.orderedStream().filter(resolver -> resolver.supports(type))
                .map(resolver -> resolver.describe(type, id)).flatMap(Optional::stream).findFirst();
    }

    private RuleView view(AccessRule rule) {
        RuleSpec spec = rule.spec();
        List<String> codes = branches.findByIds(spec.branchIds()).stream().map(BranchInfo::code).sorted().toList();
        return new RuleView(rule.getId(), spec.scopeType(), spec.scopeId(),
                describe(spec.scopeType(), spec.scopeId()).orElse("(missing)"), spec.principalType(),
                spec.principalId(), spec.actions().stream().sorted().toList(), spec.effect(), spec.branchConstraint(),
                codes, spec.validFrom(), spec.validTo(), spec.description(), rule.getVersionLock());
    }

    private AccessRule load(UUID id) {
        return rules.findById(id).orElseThrow(() -> ApiException.notFound(RULE_NOT_FOUND, "Access rule not found."));
    }

    private AccessRule loadAtVersion(UUID id, long expectedVersion) {
        AccessRule rule = load(id);
        if (rule.getVersionLock() != expectedVersion) {
            throw ApiException.staleVersion();
        }
        return rule;
    }

    private static ApiException lockout() {
        return new ApiException(HttpStatus.CONFLICT, LAST_ADMIN_RULE,
                "This is the last tenant-wide rule granting ADMIN_ACCESS; removing it would lock administrators out.");
    }

    private static String summary(RuleSpec spec) {
        return spec.effect() + " " + spec.principalType() + " " + spec.principalId() + " "
                + spec.actions().stream().map(Enum::name).sorted().collect(Collectors.joining(",")) + " on "
                + spec.scopeType() + (spec.scopeType() == ScopeType.TENANT ? "" : " " + spec.scopeId())
                + " (branches " + spec.branchConstraint() + ")";
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
