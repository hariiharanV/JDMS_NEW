package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.Decision;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.SearchFilter;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.shared.tenant.TenantScope;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link AccessPolicy} over the tenant's approved rules (cached ≤ TTL) and the subject's live document grants (not
 * cached, so expiry and revocation apply at once).
 */
@Service
class AccessPolicyService implements AccessPolicy {

    private final AccessRuleRepository rules;
    private final DocumentGrantRepository grants;
    private final RuleCache cache;
    private final DenialAuditor denialAuditor;
    private final TransactionTemplate transactions;

    AccessPolicyService(AccessRuleRepository rules, DocumentGrantRepository grants, RuleCache cache,
            DenialAuditor denialAuditor, TransactionTemplate transactions) {
        this.rules = rules;
        this.grants = grants;
        this.cache = cache;
        this.denialAuditor = denialAuditor;
        this.transactions = transactions;
    }

    @Override
    public Decision decide(Subject subject, Action action, ResourceRef resource) {
        if (!subject.tenantId().equals(resource.tenantId())) {
            return PolicyEngine.decide(subject, action, resource, List.of(), List.of(), Instant.now());
        }
        return PolicyEngine.decide(subject, action, resource, rulesOf(subject), grantsOf(subject, resource),
                Instant.now());
    }

    @Override
    public SearchFilter searchFilter(Subject subject, Action action) {
        return PolicyEngine.filter(subject, action, rulesOf(subject), grantsOf(subject, null), Instant.now());
    }

    @Override
    public Decision check(Action action, ResourceRef resource) {
        Decision decision = decide(Subject.current(), action, resource);
        if (!decision.allowed()) {
            denialAuditor.denied(action, resource, decision);
        }
        return decision;
    }

    private List<CompiledRule> rulesOf(Subject subject) {
        return cache.get(subject.tenantId(), () -> TenantScope.call(subject.tenantId(), () -> transactions.execute(
                status -> rules.findAll().stream().map(AccessRule::compile).toList())));
    }

    private List<ActiveGrant> grantsOf(Subject subject, ResourceRef resource) {
        if (resource != null && !(resource instanceof ResourceRef.Document)) {
            return List.of();
        }
        return TenantScope.call(subject.tenantId(), () -> transactions.execute(status -> grants
                .findByUserIdAndValidUntilAfterAndRevokedAtIsNull(subject.userId(), Instant.now()).stream()
                .map(DocumentGrant::active).toList()));
    }
}
