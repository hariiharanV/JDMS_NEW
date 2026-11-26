package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.Decision;
import com.jmr.jdms.access.api.DecisionReason;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.ScopeType;
import com.jmr.jdms.access.api.SearchFilter;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.shared.Classification;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The authorization rules of spec §6, evaluated in order; the first denial ends evaluation and nothing allowed means
 * denied:
 * <ol>
 *   <li>tenant boundary (no override)</li>
 *   <li>records state: a legal hold or declared record blocks edit, check-out, delete and dispose</li>
 *   <li>explicit DENY rule for the user, one of their roles, or the document</li>
 *   <li>classification: clearance must cover the document's classification</li>
 *   <li>ALLOW rule for the user or a role on the resource's scope (tenant, cabinet, type, folder subtree, document)
 *       whose branch condition covers the document's branch (steps 5 and 6)</li>
 *   <li>a valid document grant for the user</li>
 * </ol>
 * Pure: everything it needs is passed in.
 */
public final class PolicyEngine {

    private static final Comparator<CompiledRule> MOST_SPECIFIC_FIRST = Comparator
            .comparingInt((CompiledRule rule) -> -rule.scopeType().ordinal()).thenComparing(CompiledRule::id);

    private PolicyEngine() {
    }

    public static Decision decide(Subject subject, Action action, ResourceRef resource, List<CompiledRule> rules,
            List<ActiveGrant> grants, Instant now) {
        if (!subject.tenantId().equals(resource.tenantId())) {
            return Decision.deny(DecisionReason.DENIED_TENANT_BOUNDARY, null);
        }
        if (resource instanceof ResourceRef.Document document && (document.onLegalHold() || document.recordDeclared())
                && Action.BLOCKED_BY_RECORDS_STATE.contains(action)) {
            return Decision.deny(DecisionReason.DENIED_RECORDS_STATE, null);
        }
        List<CompiledRule> applicable = rules.stream()
                .filter(rule -> rule.activeAt(now) && rule.actions().contains(action) && appliesTo(rule, subject)
                        && contains(rule, resource))
                .sorted(MOST_SPECIFIC_FIRST).toList();
        Optional<CompiledRule> deny = applicable.stream().filter(rule -> rule.effect() == Effect.DENY).findFirst();
        if (deny.isPresent()) {
            return Decision.deny(DecisionReason.DENIED_EXPLICIT_RULE, deny.get().id());
        }
        Classification classification = classificationOf(resource);
        if (classification != null && !subject.clearance().covers(classification)) {
            return Decision.deny(DecisionReason.DENIED_CLEARANCE, null);
        }
        Optional<CompiledRule> allow = applicable.stream()
                .filter(rule -> rule.effect() == Effect.ALLOW && branchAllowed(rule, subject, branchOf(resource)))
                .findFirst();
        if (allow.isPresent()) {
            return Decision.allow(DecisionReason.ALLOWED_BY_RULE, allow.get().id());
        }
        if (resource instanceof ResourceRef.Document document) {
            Optional<ActiveGrant> grant = grants.stream().filter(candidate -> candidate.validAt(now)
                    && candidate.documentId().equals(document.documentId()) && candidate.actions().contains(action))
                    .findFirst();
            if (grant.isPresent()) {
                return Decision.allow(DecisionReason.ALLOWED_BY_GRANT, grant.get().id());
            }
        }
        return Decision.deny(DecisionReason.DENIED_NO_PERMISSION, null);
    }

    /**
     * The documents the subject may act on with {@code action}, for read-type actions (records state is not part of
     * the filter; write paths use {@link #decide}).
     */
    public static SearchFilter filter(Subject subject, Action action, List<CompiledRule> rules,
            List<ActiveGrant> grants, Instant now) {
        Set<Classification> cleared = Arrays.stream(Classification.values())
                .filter(level -> subject.clearance().covers(level)).collect(Collectors.toSet());
        List<CompiledRule> applicable = rules.stream()
                .filter(rule -> rule.activeAt(now) && rule.actions().contains(action) && appliesTo(rule, subject)
                        && (rule.scopeType() != ScopeType.TENANT || rule.scopeId().equals(subject.tenantId())))
                .toList();
        List<SearchFilter.Scope> deny = applicable.stream().filter(rule -> rule.effect() == Effect.DENY)
                .map(rule -> new SearchFilter.Scope(rule.scopeType(), rule.scopeId(), true, Set.of())).toList();
        List<SearchFilter.Scope> allow = applicable.stream().filter(rule -> rule.effect() == Effect.ALLOW)
                .map(rule -> switch (rule.branchConstraint()) {
                    case ALL -> new SearchFilter.Scope(rule.scopeType(), rule.scopeId(), true, Set.of());
                    case LIST -> new SearchFilter.Scope(rule.scopeType(), rule.scopeId(), false, rule.branchIds());
                    case OWN -> new SearchFilter.Scope(rule.scopeType(), rule.scopeId(), subject.branchScope().all(),
                            subject.branchScope().branchIds());
                })
                .toList();
        Set<UUID> granted = grants.stream().filter(grant -> grant.validAt(now) && grant.actions().contains(action))
                .map(ActiveGrant::documentId).collect(Collectors.toSet());
        return new SearchFilter(cleared, allow, deny, granted);
    }

    private static boolean appliesTo(CompiledRule rule, Subject subject) {
        return switch (rule.principalKind()) {
            case ROLE -> subject.roles().contains(rule.principalId());
            case USER -> subject.userId().toString().equals(rule.principalId());
        };
    }

    private static boolean contains(CompiledRule rule, ResourceRef resource) {
        if (rule.scopeType() == ScopeType.TENANT) {
            return rule.scopeId().equals(resource.tenantId());
        }
        return switch (resource) {
            case ResourceRef.Tenant tenant -> false;
            case ResourceRef.NewDocument document -> switch (rule.scopeType()) {
                case CABINET -> rule.scopeId().equals(document.cabinetId());
                case DOCUMENT_TYPE -> rule.scopeId().equals(document.documentTypeId());
                case FOLDER -> document.folderPath().contains(rule.scopeId());
                default -> false;
            };
            case ResourceRef.Document document -> switch (rule.scopeType()) {
                case CABINET -> rule.scopeId().equals(document.cabinetId());
                case DOCUMENT_TYPE -> rule.scopeId().equals(document.documentTypeId());
                case FOLDER -> document.folderPath().contains(rule.scopeId());
                case DOCUMENT -> rule.scopeId().equals(document.documentId());
                case TENANT -> false;
            };
        };
    }

    private static boolean branchAllowed(CompiledRule rule, Subject subject, UUID branchId) {
        if (branchId == null) {
            return true; // tenant-wide actions have no branch
        }
        return switch (rule.branchConstraint()) {
            case ALL -> true;
            case OWN -> subject.branchScope().covers(branchId);
            case LIST -> rule.branchIds().contains(branchId);
        };
    }

    private static UUID branchOf(ResourceRef resource) {
        return switch (resource) {
            case ResourceRef.Tenant tenant -> null;
            case ResourceRef.NewDocument document -> document.branchId();
            case ResourceRef.Document document -> document.branchId();
        };
    }

    private static Classification classificationOf(ResourceRef resource) {
        return switch (resource) {
            case ResourceRef.Tenant tenant -> null;
            case ResourceRef.NewDocument document -> document.classification();
            case ResourceRef.Document document -> document.classification();
        };
    }
}
