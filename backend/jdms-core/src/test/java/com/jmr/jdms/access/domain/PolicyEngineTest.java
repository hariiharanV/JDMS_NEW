package com.jmr.jdms.access.domain;

import static com.jmr.jdms.access.api.Action.ADMIN_ACCESS;
import static com.jmr.jdms.access.api.Action.ADMIN_METADATA;
import static com.jmr.jdms.access.api.Action.CHECK_OUT;
import static com.jmr.jdms.access.api.Action.DELETE;
import static com.jmr.jdms.access.api.Action.DOWNLOAD;
import static com.jmr.jdms.access.api.Action.EDIT_METADATA;
import static com.jmr.jdms.access.api.Action.UPLOAD;
import static com.jmr.jdms.access.api.Action.VIEW_METADATA;
import static com.jmr.jdms.access.api.DecisionReason.ALLOWED_BY_GRANT;
import static com.jmr.jdms.access.api.DecisionReason.ALLOWED_BY_RULE;
import static com.jmr.jdms.access.api.DecisionReason.DENIED_CLEARANCE;
import static com.jmr.jdms.access.api.DecisionReason.DENIED_EXPLICIT_RULE;
import static com.jmr.jdms.access.api.DecisionReason.DENIED_NO_PERMISSION;
import static com.jmr.jdms.access.api.DecisionReason.DENIED_RECORDS_STATE;
import static com.jmr.jdms.access.api.DecisionReason.DENIED_TENANT_BOUNDARY;
import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.Decision;
import com.jmr.jdms.access.api.DecisionReason;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.ScopeType;
import com.jmr.jdms.access.api.SearchFilter;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.BranchScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Spec §6 evaluation order, one assertion per situation. Expected outcomes are written out, not computed.
 * Document D: tenant T, cabinet C, type X, folder F2 (inside F), branch B1, CONFIDENTIAL.
 */
class PolicyEngineTest {

    static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    static final UUID T = UUID.randomUUID();
    static final UUID OTHER_TENANT = UUID.randomUUID();
    static final UUID B1 = UUID.randomUUID();
    static final UUID B2 = UUID.randomUUID();
    static final UUID C = UUID.randomUUID();
    static final UUID X = UUID.randomUUID();
    static final UUID F = UUID.randomUUID();
    static final UUID F2 = UUID.randomUUID();
    static final UUID D = UUID.randomUUID();
    static final UUID DENIED_USER = UUID.randomUUID();

    static final CompiledRule MAKER = rule(ScopeType.TENANT, T, PrincipalKind.ROLE, "MAKER",
            Set.of(VIEW_METADATA, DOWNLOAD, EDIT_METADATA, CHECK_OUT, DELETE, UPLOAD), Effect.ALLOW,
            BranchConstraint.OWN, Set.of(), null, null);
    static final CompiledRule VIEWER = rule(ScopeType.TENANT, T, PrincipalKind.ROLE, "VIEWER", Set.of(VIEW_METADATA),
            Effect.ALLOW, BranchConstraint.OWN, Set.of(), null, null);
    static final CompiledRule HEAD_OFFICE = rule(ScopeType.TENANT, T, PrincipalKind.ROLE, "HQ", Set.of(VIEW_METADATA),
            Effect.ALLOW, BranchConstraint.ALL, Set.of(), null, null);
    static final CompiledRule REGION = rule(ScopeType.CABINET, C, PrincipalKind.ROLE, "REGION", Set.of(VIEW_METADATA),
            Effect.ALLOW, BranchConstraint.LIST, Set.of(B2), null, null);
    static final CompiledRule FOLDER = rule(ScopeType.FOLDER, F, PrincipalKind.ROLE, "FOLDER_TEAM",
            Set.of(VIEW_METADATA), Effect.ALLOW, BranchConstraint.ALL, Set.of(), null, null);
    static final CompiledRule TYPE = rule(ScopeType.DOCUMENT_TYPE, X, PrincipalKind.ROLE, "LOANS_TEAM", Set.of(DOWNLOAD),
            Effect.ALLOW, BranchConstraint.OWN, Set.of(), null, null);
    static final CompiledRule DENY_USER_ON_D = rule(ScopeType.DOCUMENT, D, PrincipalKind.USER, DENIED_USER.toString(),
            Set.of(VIEW_METADATA), Effect.DENY, BranchConstraint.ALL, Set.of(), null, null);
    static final CompiledRule EXPIRED = rule(ScopeType.TENANT, T, PrincipalKind.ROLE, "TEMP", Set.of(VIEW_METADATA),
            Effect.ALLOW, BranchConstraint.ALL, Set.of(), NOW.minus(Duration.ofDays(10)), NOW.minusSeconds(1));
    static final CompiledRule FUTURE = rule(ScopeType.TENANT, T, PrincipalKind.ROLE, "SOON", Set.of(VIEW_METADATA),
            Effect.ALLOW, BranchConstraint.ALL, Set.of(), NOW.plusSeconds(60), null);
    static final CompiledRule TENANT_ADMIN = rule(ScopeType.TENANT, T, PrincipalKind.ROLE, "TENANT_ADMIN",
            Set.of(ADMIN_METADATA), Effect.ALLOW, BranchConstraint.ALL, Set.of(), null, null);
    static final CompiledRule SECURITY_ADMIN = rule(ScopeType.TENANT, T, PrincipalKind.ROLE, "SECURITY_ADMIN",
            Set.of(ADMIN_ACCESS), Effect.ALLOW, BranchConstraint.ALL, Set.of(), null, null);

    static final List<CompiledRule> RULES = List.of(MAKER, VIEWER, HEAD_OFFICE, REGION, FOLDER, TYPE, DENY_USER_ON_D,
            EXPIRED, FUTURE, TENANT_ADMIN, SECURITY_ADMIN);

    static final ResourceRef.Document DOC = document(false, false, B1);

    // ---- step 1: tenant boundary
    @Test
    void anotherTenantIsAlwaysDenied() {
        Subject outsider = new Subject(OTHER_TENANT, UUID.randomUUID(), Set.of("HQ"), BranchScope.ALL,
                Classification.RESTRICTED);
        expect(decide(outsider, VIEW_METADATA, DOC), DENIED_TENANT_BOUNDARY, null);
    }

    // ---- step 2: records state
    @Test
    void legalHoldAndDeclaredRecordsBlockChangesButNotReading() {
        Subject maker = subject("MAKER", BranchScope.of(Set.of(B1)), Classification.CONFIDENTIAL);
        ResourceRef.Document held = document(true, false, B1);
        ResourceRef.Document record = document(false, true, B1);

        expect(decide(maker, EDIT_METADATA, held), DENIED_RECORDS_STATE, null);
        expect(decide(maker, CHECK_OUT, held), DENIED_RECORDS_STATE, null);
        expect(decide(maker, DELETE, record), DENIED_RECORDS_STATE, null);
        expect(decide(maker, VIEW_METADATA, held), ALLOWED_BY_RULE, MAKER.id());
        expect(decide(maker, DOWNLOAD, record), ALLOWED_BY_RULE, MAKER.id());
    }

    // ---- step 3: explicit deny
    @Test
    void explicitDenyWinsOverAnyAllowAndIsCheckedBeforeClearance() {
        Subject denied = new Subject(T, DENIED_USER, Set.of("MAKER", "HQ"), BranchScope.ALL, Classification.PUBLIC);

        expect(decide(denied, VIEW_METADATA, DOC), DENIED_EXPLICIT_RULE, DENY_USER_ON_D.id());
        // the deny is scoped to D and VIEW_METADATA only
        expect(decide(new Subject(T, DENIED_USER, Set.of("MAKER"), BranchScope.ALL, Classification.CONFIDENTIAL),
                DOWNLOAD, DOC), ALLOWED_BY_RULE, MAKER.id());
    }

    // ---- step 4: classification
    @Test
    void clearanceMustCoverTheClassification() {
        expect(decide(subject("MAKER", BranchScope.of(Set.of(B1)), Classification.INTERNAL), VIEW_METADATA, DOC),
                DENIED_CLEARANCE, null);
        expect(decide(subject("MAKER", BranchScope.of(Set.of(B1)), Classification.CONFIDENTIAL), VIEW_METADATA, DOC),
                ALLOWED_BY_RULE, MAKER.id());
    }

    // ---- steps 5 and 6: role rules on scopes, branch conditions
    @Test
    void ownBranchRulesNeedTheUsersBranchScope() {
        expect(decide(subject("MAKER", BranchScope.of(Set.of(B1)), Classification.CONFIDENTIAL), DOWNLOAD, DOC),
                ALLOWED_BY_RULE, MAKER.id());
        expect(decide(subject("MAKER", BranchScope.of(Set.of(B2)), Classification.CONFIDENTIAL), DOWNLOAD, DOC),
                DENIED_NO_PERMISSION, null);
        expect(decide(subject("MAKER", BranchScope.ALL, Classification.CONFIDENTIAL), DOWNLOAD, DOC),
                ALLOWED_BY_RULE, MAKER.id());
        expect(decide(subject("VIEWER", BranchScope.of(Set.of(B1)), Classification.CONFIDENTIAL), DOWNLOAD, DOC),
                DENIED_NO_PERMISSION, null);
    }

    @Test
    void allAndListBranchConstraints() {
        expect(decide(subject("HQ", BranchScope.of(Set.of(B2)), Classification.CONFIDENTIAL), VIEW_METADATA, DOC),
                ALLOWED_BY_RULE, HEAD_OFFICE.id());
        expect(decide(subject("REGION", BranchScope.of(Set.of(B1)), Classification.CONFIDENTIAL), VIEW_METADATA, DOC),
                DENIED_NO_PERMISSION, null);
        expect(decide(subject("REGION", BranchScope.of(Set.of(B1)), Classification.CONFIDENTIAL), VIEW_METADATA,
                document(false, false, B2)), ALLOWED_BY_RULE, REGION.id());
    }

    @Test
    void folderRulesCoverSubFoldersAndTypeRulesTheirType() {
        expect(decide(subject("FOLDER_TEAM", BranchScope.NONE, Classification.CONFIDENTIAL), VIEW_METADATA, DOC),
                ALLOWED_BY_RULE, FOLDER.id());
        ResourceRef.Document elsewhere = new ResourceRef.Document(T, UUID.randomUUID(), C, X, List.of(), B1,
                Classification.CONFIDENTIAL, false, false);
        expect(decide(subject("FOLDER_TEAM", BranchScope.NONE, Classification.CONFIDENTIAL), VIEW_METADATA, elsewhere),
                DENIED_NO_PERMISSION, null);
        expect(decide(subject("LOANS_TEAM", BranchScope.of(Set.of(B1)), Classification.CONFIDENTIAL), DOWNLOAD, DOC),
                ALLOWED_BY_RULE, TYPE.id());
    }

    @Test
    void rulesOutsideTheirValidityDoNotApply() {
        expect(decide(subject("TEMP", BranchScope.ALL, Classification.RESTRICTED), VIEW_METADATA, DOC),
                DENIED_NO_PERMISSION, null);
        expect(decide(subject("SOON", BranchScope.ALL, Classification.RESTRICTED), VIEW_METADATA, DOC),
                DENIED_NO_PERMISSION, null);
    }

    @Test
    void tenantWideActionsAndUpload() {
        UUID tenantId = T;
        expect(decide(subject("TENANT_ADMIN", BranchScope.NONE, Classification.PUBLIC), ADMIN_METADATA,
                new ResourceRef.Tenant(tenantId)), ALLOWED_BY_RULE, TENANT_ADMIN.id());
        expect(decide(subject("MAKER", BranchScope.ALL, Classification.RESTRICTED), ADMIN_METADATA,
                new ResourceRef.Tenant(tenantId)), DENIED_NO_PERMISSION, null);
        expect(decide(subject("SECURITY_ADMIN", BranchScope.NONE, Classification.PUBLIC), ADMIN_ACCESS,
                new ResourceRef.Tenant(tenantId)), ALLOWED_BY_RULE, SECURITY_ADMIN.id());

        ResourceRef.NewDocument intoB1 = new ResourceRef.NewDocument(T, C, X, List.of(), B1, Classification.INTERNAL);
        expect(decide(subject("MAKER", BranchScope.of(Set.of(B1)), Classification.INTERNAL), UPLOAD, intoB1),
                ALLOWED_BY_RULE, MAKER.id());
        expect(decide(subject("MAKER", BranchScope.of(Set.of(B2)), Classification.INTERNAL), UPLOAD, intoB1),
                DENIED_NO_PERMISSION, null);
        expect(decide(subject("MAKER", BranchScope.of(Set.of(B1)), Classification.PUBLIC), UPLOAD, intoB1),
                DENIED_CLEARANCE, null);
    }

    // ---- step 7: grants
    @Test
    void validGrantsAllowWithinClearanceAndDenyRules() {
        UUID user = UUID.randomUUID();
        ActiveGrant valid = new ActiveGrant(UUID.randomUUID(), D, Set.of(VIEW_METADATA), NOW.plusSeconds(3600), null);
        ActiveGrant expired = new ActiveGrant(UUID.randomUUID(), D, Set.of(VIEW_METADATA), NOW.minusSeconds(1), null);
        ActiveGrant revoked = new ActiveGrant(UUID.randomUUID(), D, Set.of(VIEW_METADATA), NOW.plusSeconds(3600),
                NOW.minusSeconds(5));
        Subject noRoles = new Subject(T, user, Set.of(), BranchScope.NONE, Classification.CONFIDENTIAL);

        expect(PolicyEngine.decide(noRoles, VIEW_METADATA, DOC, RULES, List.of(valid), NOW), ALLOWED_BY_GRANT,
                valid.id());
        expect(PolicyEngine.decide(noRoles, DOWNLOAD, DOC, RULES, List.of(valid), NOW), DENIED_NO_PERMISSION, null);
        expect(PolicyEngine.decide(noRoles, VIEW_METADATA, DOC, RULES, List.of(expired), NOW),
                DENIED_NO_PERMISSION, null);
        expect(PolicyEngine.decide(noRoles, VIEW_METADATA, DOC, RULES, List.of(revoked), NOW),
                DENIED_NO_PERMISSION, null);
        expect(PolicyEngine.decide(new Subject(T, user, Set.of(), BranchScope.NONE, Classification.INTERNAL),
                VIEW_METADATA, DOC, RULES, List.of(valid), NOW), DENIED_CLEARANCE, null);
        expect(PolicyEngine.decide(new Subject(T, DENIED_USER, Set.of(), BranchScope.NONE,
                Classification.CONFIDENTIAL), VIEW_METADATA, DOC, RULES, List.of(valid), NOW),
                DENIED_EXPLICIT_RULE, DENY_USER_ON_D.id());
    }

    // ---- search filter
    @Test
    void searchFilterMirrorsTheRules() {
        UUID user = UUID.randomUUID();
        ActiveGrant grant = new ActiveGrant(UUID.randomUUID(), D, Set.of(VIEW_METADATA), NOW.plusSeconds(60), null);
        SearchFilter filter = PolicyEngine.filter(new Subject(T, user, Set.of("MAKER", "REGION"),
                BranchScope.of(Set.of(B1)), Classification.INTERNAL), VIEW_METADATA, RULES, List.of(grant), NOW);

        assertThat(filter.classifications()).containsExactlyInAnyOrder(Classification.PUBLIC, Classification.INTERNAL);
        assertThat(filter.allowScopes()).containsExactlyInAnyOrder(
                new SearchFilter.Scope(ScopeType.TENANT, T, false, Set.of(B1)),
                new SearchFilter.Scope(ScopeType.CABINET, C, false, Set.of(B2)));
        assertThat(filter.denyScopes()).isEmpty();
        assertThat(filter.grantedDocumentIds()).containsExactly(D);

        SearchFilter denied = PolicyEngine.filter(new Subject(T, DENIED_USER, Set.of("HQ"), BranchScope.ALL,
                Classification.RESTRICTED), VIEW_METADATA, RULES, List.of(), NOW);
        assertThat(denied.denyScopes()).containsExactly(new SearchFilter.Scope(ScopeType.DOCUMENT, D, true, Set.of()));

        assertThat(PolicyEngine.filter(subject("NOBODY", BranchScope.ALL, Classification.RESTRICTED), VIEW_METADATA,
                RULES, List.of(), NOW).matchesNothing()).isTrue();
    }

    private static Decision decide(Subject subject, Action action, ResourceRef resource) {
        return PolicyEngine.decide(subject, action, resource, RULES, List.of(), NOW);
    }

    private static void expect(Decision decision, DecisionReason reason, UUID decidedBy) {
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.allowed()).isEqualTo(reason == ALLOWED_BY_RULE || reason == ALLOWED_BY_GRANT);
        assertThat(decision.decidedBy()).isEqualTo(decidedBy);
    }

    private static Subject subject(String role, BranchScope scope, Classification clearance) {
        return new Subject(T, UUID.randomUUID(), Set.of(role), scope, clearance);
    }

    private static ResourceRef.Document document(boolean held, boolean record, UUID branch) {
        return new ResourceRef.Document(T, D, C, X, List.of(F2, F), branch, Classification.CONFIDENTIAL, held, record);
    }

    private static CompiledRule rule(ScopeType scopeType, UUID scopeId, PrincipalKind kind, String principal,
            Set<Action> actions, Effect effect, BranchConstraint branches, Set<UUID> branchIds, Instant from,
            Instant to) {
        return new CompiledRule(UUID.randomUUID(), scopeType, scopeId, kind, principal, actions, effect, branches,
                branchIds, from, to);
    }
}
