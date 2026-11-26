package com.jmr.jdms.access.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Gives a tenant without rules the baseline rule set (the PoC actor matrix), so it can be administered at all. */
public interface AccessProvisioning {

    /** Creates the baseline rules if the tenant has no rules yet; returns whether it did. */
    boolean provisionBaseline(UUID tenantId);

    /**
     * Recreates a legacy JDMS document's role permissions as allow rules on that document (ADR-0020), in the caller's
     * transaction and tenant. Only the import of legacy documents uses this: it reproduces access already approved
     * in the legacy system; new access always goes through maker-checker.
     *
     * @return the number of rules created
     */
    int importDocumentRules(UUID documentId, List<ImportedRule> rules);

    /** One legacy role permission: the JDMS role it maps to and the actions it allows. */
    record ImportedRule(String role, Set<Action> actions, String description) {

        public ImportedRule {
            actions = Set.copyOf(actions);
        }
    }
}
