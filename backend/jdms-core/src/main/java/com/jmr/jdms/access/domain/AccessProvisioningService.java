package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.AccessProvisioning;
import com.jmr.jdms.access.api.ScopeType;
import com.jmr.jdms.audit.api.ActorType;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.AccessRulesChanged;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.TenantDirectory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Provisions baseline rules for tenants without rules, at startup and on request (tenant onboarding, tests). */
@Service
class AccessProvisioningService implements AccessProvisioning, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccessProvisioningService.class);

    private final AccessRuleRepository rules;
    private final TenantDirectory tenants;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    AccessProvisioningService(AccessRuleRepository rules, TenantDirectory tenants, AuditTrail auditTrail,
            ApplicationEventPublisher events, TransactionTemplate transactions) {
        this.rules = rules;
        this.tenants = tenants;
        this.auditTrail = auditTrail;
        this.events = events;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        tenants.findAllActive().forEach(tenant -> {
            if (provisionBaseline(tenant.id())) {
                log.info("Provisioned baseline access rules for tenant {}", tenant.code());
            }
        });
    }

    @Override
    public boolean provisionBaseline(UUID tenantId) {
        return Boolean.TRUE.equals(TenantScope.call(tenantId, () -> transactions.execute(status -> {
            if (rules.count() > 0) {
                return false;
            }
            BaselineRules.forTenant(tenantId).forEach(spec -> rules.save(new AccessRule(null, spec)));
            auditTrail.record(AuditEntry.success("ACCESS_BASELINE_PROVISIONED", "TENANT", tenantId,
                    Map.of("rules", rules.count())).byActor(ActorType.SYSTEM, "system"));
            events.publishEvent(new AccessRulesChanged(tenantId));
            return true;
        })));
    }

    @Override
    public int importDocumentRules(UUID documentId, List<ImportedRule> imported) {
        UUID tenantId = TenantScope.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("Importing rules needs a tenant in TenantScope"));
        for (ImportedRule rule : imported) {
            rules.save(new AccessRule(null, new RuleSpec(ScopeType.DOCUMENT, documentId, PrincipalKind.ROLE,
                    rule.role(), rule.actions(), Effect.ALLOW, BranchConstraint.ALL, Set.of(), null, null,
                    rule.description())));
        }
        if (!imported.isEmpty()) {
            auditTrail.record(AuditEntry.success("ACCESS_RULES_IMPORTED", "DOCUMENT", documentId,
                    Map.of("rules", imported.size(), "roles", imported.stream().map(ImportedRule::role).sorted()
                            .toList())));
            events.publishEvent(new AccessRulesChanged(tenantId));
        }
        return imported.size();
    }
}
