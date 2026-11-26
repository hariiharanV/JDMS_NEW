package com.jmr.jdms;

import com.jmr.jdms.records.domain.DispositionAction;
import com.jmr.jdms.records.domain.DispositionBatch;
import com.jmr.jdms.records.domain.DispositionBatchRepository;
import com.jmr.jdms.records.domain.DispositionItem;
import com.jmr.jdms.records.domain.DispositionItemRepository;
import com.jmr.jdms.records.domain.LegalHold;
import com.jmr.jdms.records.domain.LegalHoldItem;
import com.jmr.jdms.records.domain.LegalHoldItemRepository;
import com.jmr.jdms.records.domain.LegalHoldRepository;
import com.jmr.jdms.records.domain.RecordEvent;
import com.jmr.jdms.records.domain.RecordEventRepository;
import com.jmr.jdms.records.domain.RetentionPolicy;
import com.jmr.jdms.records.domain.RetentionPolicyRepository;
import com.jmr.jdms.records.domain.RetentionTrigger;
import com.jmr.jdms.records.domain.Tombstone;
import com.jmr.jdms.records.domain.TombstoneRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.GrantSource;
import com.jmr.jdms.access.domain.AccessRule;
import com.jmr.jdms.access.domain.AccessRuleRepository;
import com.jmr.jdms.access.domain.DocumentGrant;
import com.jmr.jdms.access.domain.DocumentGrantRepository;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.audit.api.AuditVerificationView;
import com.jmr.jdms.audit.domain.AuditEvent;
import com.jmr.jdms.audit.domain.AuditEventRepository;
import com.jmr.jdms.audit.domain.AuditVerification;
import com.jmr.jdms.audit.domain.AuditVerifier;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.document.domain.ApprovalStatus;
import com.jmr.jdms.document.domain.CabinetUsage;
import com.jmr.jdms.document.domain.Document;
import com.jmr.jdms.document.domain.DocumentMetadata;
import com.jmr.jdms.document.domain.DocumentRepository;
import com.jmr.jdms.document.domain.DocumentStatus;
import com.jmr.jdms.document.domain.DocumentVersion;
import com.jmr.jdms.document.domain.DocumentVersionRepository;
import com.jmr.jdms.document.domain.Capture;
import com.jmr.jdms.document.domain.CaptureRepository;
import com.jmr.jdms.document.domain.Rendition;
import com.jmr.jdms.document.domain.RenditionRepository;
import com.jmr.jdms.document.domain.StandardMetadata;
import com.jmr.jdms.document.domain.StoredContent;
import com.jmr.jdms.identity.domain.BranchScopeType;
import com.jmr.jdms.identity.domain.Delegation;
import com.jmr.jdms.identity.domain.DelegationRepository;
import com.jmr.jdms.identity.domain.Notification;
import com.jmr.jdms.identity.domain.NotificationRepository;
import com.jmr.jdms.identity.domain.Role;
import com.jmr.jdms.identity.domain.RoleRepository;
import com.jmr.jdms.identity.domain.UserBranchScope;
import com.jmr.jdms.identity.domain.UserBranchScopeRepository;
import com.jmr.jdms.identity.domain.UserProfile;
import com.jmr.jdms.identity.domain.UserProfileRepository;
import com.jmr.jdms.identity.domain.UserRole;
import com.jmr.jdms.identity.domain.UserRoleRepository;
import com.jmr.jdms.metadata.domain.Cabinet;
import com.jmr.jdms.metadata.domain.CabinetRepository;
import com.jmr.jdms.metadata.domain.DocumentType;
import com.jmr.jdms.metadata.domain.DocumentTypeAttributes;
import com.jmr.jdms.metadata.domain.DocumentTypeRepository;
import com.jmr.jdms.metadata.domain.FieldDataType;
import com.jmr.jdms.metadata.domain.FieldDefinition;
import com.jmr.jdms.metadata.domain.FieldDefinitionRepository;
import com.jmr.jdms.metadata.domain.FieldSpec;
import com.jmr.jdms.metadata.domain.Folder;
import com.jmr.jdms.metadata.domain.FolderRepository;
import com.jmr.jdms.metadata.domain.Lov;
import com.jmr.jdms.metadata.domain.LovRepository;
import com.jmr.jdms.metadata.domain.LovValue;
import com.jmr.jdms.metadata.domain.LovValueRepository;
import com.jmr.jdms.metadata.domain.UploadChannel;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.BranchScope;
import com.jmr.jdms.shared.context.PrincipalType;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.idempotency.IdempotencyRecord;
import com.jmr.jdms.shared.idempotency.IdempotencyRecordRepository;
import com.jmr.jdms.shared.idempotency.IdempotencyStore;
import com.jmr.jdms.shared.job.Job;
import com.jmr.jdms.shared.job.JobQueue;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.tenant.domain.Branch;
import com.jmr.jdms.tenant.domain.BranchRepository;
import com.jmr.jdms.tenant.domain.Tenant;
import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.ChangeProposal;
import com.jmr.jdms.workflow.api.ChangeRequests;
import com.jmr.jdms.workflow.domain.ApprovalRecord;
import com.jmr.jdms.workflow.domain.ApprovalRecordRepository;
import com.jmr.jdms.workflow.domain.Committee;
import com.jmr.jdms.workflow.domain.CommitteeMember;
import com.jmr.jdms.workflow.domain.CommitteeMemberRepository;
import com.jmr.jdms.workflow.domain.CommitteeRepository;
import com.jmr.jdms.workflow.domain.PendingChange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B-01 done criterion: no cross-tenant read or write is possible through any repository. Every JPA repository of
 * the application is exercised; adding an entity or repository without a fixture here fails the test.
 */
@IntegrationTest
class TenantIsolationIT {

    @Autowired TestTenants testTenants;
    @Autowired TransactionTemplate transactions;
    @Autowired ApplicationContext applicationContext;
    @Autowired EntityManager entityManager;
    @Autowired BranchRepository branches;
    @Autowired UserProfileRepository profiles;
    @Autowired UserBranchScopeRepository branchScopes;
    @Autowired RoleRepository roles;
    @Autowired UserRoleRepository userRoles;
    @Autowired DelegationRepository delegations;
    @Autowired AuditEventRepository auditEvents;
    @Autowired AuditVerifier auditVerifier;
    @Autowired AuditTrail auditTrail;
    @Autowired CabinetRepository cabinets;
    @Autowired FolderRepository folders;
    @Autowired DocumentTypeRepository documentTypes;
    @Autowired FieldDefinitionRepository fieldDefinitions;
    @Autowired LovRepository lovs;
    @Autowired LovValueRepository lovValues;
    @Autowired ChangeRequests changeRequests;
    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository documentVersions;
    @Autowired JobQueue jobQueue;
    @Autowired IdempotencyStore idempotencyStore;
    @Autowired IdempotencyRecordRepository idempotencyRecords;
    @Autowired AccessRuleRepository accessRules;
    @Autowired DocumentGrantRepository documentGrants;
    @Autowired ApprovalRecordRepository approvalRecords;
    @Autowired RenditionRepository renditionRepository;
    @Autowired CaptureRepository captureRepository;
    @Autowired RetentionPolicyRepository retentionPolicies;
    @Autowired LegalHoldRepository legalHolds;
    @Autowired LegalHoldItemRepository legalHoldItems;
    @Autowired RecordEventRepository recordEvents;
    @Autowired DispositionBatchRepository dispositionBatches;
    @Autowired DispositionItemRepository dispositionItems;
    @Autowired TombstoneRepository tombstones;
    @Autowired NotificationRepository notifications;
    @Autowired CommitteeRepository committees;
    @Autowired CommitteeMemberRepository committeeMembers;

    CreatedTenant tenantA;
    CreatedTenant tenantB;
    /** ID of the row created in tenant A, per entity type. */
    final Map<Class<?>, Object> idsInA = new HashMap<>();
    String usernameA;
    String roleCodeA;

    @BeforeEach
    void createDataInTenantA() {
        tenantA = testTenants.create("001");
        tenantB = testTenants.create("001");
        usernameA = "alice-" + tenantA.code().toLowerCase();
        roleCodeA = "ROLE_" + tenantA.code();
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        inTenant(tenantA, () -> {
            UserProfile alice = profiles.save(profile(userId, usernameA));
            profiles.save(profile(otherUserId, "bob-" + tenantA.code().toLowerCase()));
            UserBranchScope scope = branchScopes.save(new UserBranchScope(alice.getId(), tenantA.branch("001")));
            Role role = roles.save(new Role(roleCodeA, "Role", null));
            UserRole userRole = userRoles.save(new UserRole(alice.getId(), role.getId()));
            Delegation delegation = delegations.save(new Delegation(alice.getId(), otherUserId, role.getId(),
                    Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS), "Leave"));
            auditTrail.record(AuditEntry.success("TEST_EVENT_" + tenantA.code(), "TEST", "1", Map.of()));
            AuditEvent auditEvent = auditEvents.findByActionOrderByOccurredAt("TEST_EVENT_" + tenantA.code()).getFirst();
            UUID verificationId = auditVerifier.verify(AuditVerificationView.Trigger.REQUEST).id();

            Cabinet cabinet = cabinets.save(new Cabinet(Ids.newId(), "CAB_" + tenantA.code(), "Cabinet", 100, 1000,
                    null));
            Folder folder = folders.save(new Folder(Ids.newId(), cabinet.getId(), null, "Folder", "/Folder"));
            DocumentType documentType = documentTypes.save(new DocumentType(Ids.newId(), cabinet.getId(),
                    "TYPE_" + tenantA.code(), new DocumentTypeAttributes("Type", "نوع", "KYC",
                            Set.of(UploadChannel.UI), Classification.INTERNAL, null, false, false, false, Set.of())));
            FieldDefinition field = fieldDefinitions.save(new FieldDefinition(Ids.newId(), documentType.getId(),
                    new FieldSpec("note", "Note", "ملاحظة", FieldDataType.TEXT, false, 50, null, null, false, false,
                            false, 1, 1)));
            Lov lov = lovs.save(new Lov(Ids.newId(), "LOV_" + tenantA.code(), "List"));
            LovValue lovValue = lovValues.save(new LovValue(lov.getId(), "A", "A", "أ", 1, true));
            UUID pendingChangeId = asUser(tenantA, alice.getId(), () -> changeRequests.submit(new ChangeProposal(
                    "CABINET", ChangeOperation.DEACTIVATE, cabinet.getId(), null, 0L, Map.of(), "Test"))).id();

            Document document = documents.save(new Document(cabinet.getId(), documentType.getId(),
                    DocumentStatus.ACTIVE, "UI", new DocumentMetadata("Doc", null, Classification.INTERNAL,
                            tenantA.branch("001"), StandardMetadata.EMPTY, "{}", null)));
            DocumentVersion version = documentVersions.save(new DocumentVersion(null, document.getId(), 1, 0,
                    ApprovalStatus.APPROVED, new StoredContent(tenantA.code() + "/blob-" + Ids.newId(), "0".repeat(64),
                            1, "wrapped", "local:v1"), "application/pdf", "a.pdf", null));
            Job job = jobQueue.enqueue(JobType.SCAN, Map.of("versionId", version.getId().toString()));
            idempotencyStore.remember(alice.getId().toString(), "key-" + tenantA.code(), "hash", 201, "{}");
            IdempotencyRecord idempotency = idempotencyRecords.findByClientIdAndKey(alice.getId().toString(),
                    "key-" + tenantA.code()).orElseThrow();

            RetentionPolicy policy = retentionPolicies.save(new RetentionPolicy(Ids.newId(), "RP_" + tenantA.code(),
                    "Policy", RetentionTrigger.CREATION_DATE, null, 12, DispositionAction.DESTROY, true));
            LegalHold hold = legalHolds.save(new LegalHold("Hold", "Reason"));
            LegalHoldItem holdItem = legalHoldItems.save(new LegalHoldItem(hold.getId(), document.getId(),
                    alice.getId(), Instant.now()));
            RecordEvent recordEvent = recordEvents.save(new RecordEvent("ACCOUNT_CLOSED", "ACC-" + tenantA.code(),
                    Instant.now()));
            DispositionBatch batch = dispositionBatches.save(new DispositionBatch(Instant.now()));
            DispositionItem dispositionItem = dispositionItems.save(new DispositionItem(batch.getId(),
                    document.getId(), policy.getId(), LocalDate.now()));
            Tombstone tombstone = tombstones.save(new Tombstone(document.getId(), documentType.getId(), Instant.now(),
                    Instant.now(), batch.getId(), alice.getId(), List.of("0".repeat(64))));
            Rendition rendition = renditionRepository.save(new Rendition(version.getId(), Rendition.THUMBNAIL,
                    "image/png", new StoredContent(tenantA.code() + "/thumb-" + Ids.newId(), "0".repeat(64), 1,
                            "wrapped", "local:v1"), false));
            Capture capture = captureRepository.save(new Capture(documentType.getId(), tenantA.branch("001"), null,
                    Classification.INTERNAL, new StoredContent(tenantA.code() + "/capture-" + Ids.newId(),
                            "0".repeat(64), 1, "wrapped", "local:v1"), Instant.now().plus(1, ChronoUnit.HOURS)));
            Notification notification = notifications.save(new Notification(alice.getId(), "TEST", document.getId(),
                    version.getId(), "Test"));
            ApprovalRecord approvalRecord = approvalRecords.findAll().getFirst(); // the pending change's submission
            Committee committee = committees.save(new Committee(Ids.newId(), "COM_" + tenantA.code(), "Committee", 1));
            CommitteeMember member = committeeMembers.save(new CommitteeMember(committee.getId(), alice.getId(),
                    "Chair"));

            AccessRule rule = accessRules.findAll().getFirst(); // baseline rule of tenant A
            DocumentGrant grant = documentGrants.save(new DocumentGrant(document.getId(), alice.getId(),
                    Set.of(Action.VIEW_METADATA), Instant.now().plus(1, ChronoUnit.DAYS), GrantSource.SHARE, null));

            idsInA.put(AccessRule.class, rule.getId());
            idsInA.put(DocumentGrant.class, grant.getId());
            idsInA.put(Document.class, document.getId());
            idsInA.put(DocumentVersion.class, version.getId());
            idsInA.put(Job.class, job.getId());
            idsInA.put(IdempotencyRecord.class, idempotency.getId());
            idsInA.put(Cabinet.class, cabinet.getId());
            idsInA.put(Folder.class, folder.getId());
            idsInA.put(DocumentType.class, documentType.getId());
            idsInA.put(FieldDefinition.class, field.getId());
            idsInA.put(Lov.class, lov.getId());
            idsInA.put(LovValue.class, lovValue.getId());
            idsInA.put(PendingChange.class, pendingChangeId);
            idsInA.put(ApprovalRecord.class, approvalRecord.getId());
            idsInA.put(Rendition.class, rendition.getId());
            entityManager.persist(new CabinetUsage(cabinet.getId()));
            idsInA.put(CabinetUsage.class, cabinet.getId());
            idsInA.put(RetentionPolicy.class, policy.getId());
            idsInA.put(LegalHold.class, hold.getId());
            idsInA.put(LegalHoldItem.class, holdItem.getId());
            idsInA.put(RecordEvent.class, recordEvent.getId());
            idsInA.put(DispositionBatch.class, batch.getId());
            idsInA.put(DispositionItem.class, dispositionItem.getId());
            idsInA.put(Tombstone.class, tombstone.getId());
            idsInA.put(Notification.class, notification.getId());
            idsInA.put(Capture.class, capture.getId());
            idsInA.put(Committee.class, committee.getId());
            idsInA.put(CommitteeMember.class, member.getId());
            idsInA.put(Branch.class, tenantA.branch("001"));
            idsInA.put(UserProfile.class, alice.getId());
            idsInA.put(UserBranchScope.class, new UserBranchScope.Key(scope.getUserId(), scope.getBranchId()));
            idsInA.put(Role.class, role.getId());
            idsInA.put(UserRole.class, new UserRole.Key(userRole.getUserId(), userRole.getRoleId()));
            idsInA.put(Delegation.class, delegation.getId());
            idsInA.put(AuditEvent.class, auditEvent.getId());
            idsInA.put(AuditVerification.class, verificationId);
            return null;
        });
    }

    @Test
    void everyEntityExceptTenantHasATenantIdColumn() {
        Set<Class<?>> entities = applicationEntities();

        assertThat(entities).contains(Tenant.class);
        assertThat(entities).filteredOn(type -> type != Tenant.class)
                .allSatisfy(type -> assertThat(hasTenantIdField(type))
                        .as("%s must have an @TenantId column (extend TenantScopedEntity)", type.getSimpleName())
                        .isTrue());
    }

    @Test
    void everyTenantScopedEntityHasAFixtureInThisTest() {
        Set<Class<?>> tenantScoped = applicationEntities().stream().filter(type -> type != Tenant.class)
                .collect(Collectors.toSet());

        assertThat(idsInA.keySet()).containsExactlyInAnyOrderElementsOf(tenantScoped);
    }

    @Test
    void noRepositoryReadsAnotherTenantsRows() {
        Repositories repositories = new Repositories(applicationContext);
        int checked = 0;
        for (Class<?> domainType : repositories) {
            if (domainType == Tenant.class || isFrameworkEntity(domainType)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            JpaRepository<Object, Object> repository = (JpaRepository<Object, Object>) repositories
                    .getRepositoryFor(domainType).orElseThrow();
            Object idInA = idsInA.get(domainType);
            assertThat(idInA).as("fixture for %s", domainType.getSimpleName()).isNotNull();

            inTenant(tenantB, () -> {
                List<Object> visible = repository.findAll();
                assertThat(visible).as("findAll %s returns only tenant B rows", domainType.getSimpleName())
                        .allSatisfy(row -> assertThat(tenantIdOf(row)).isEqualTo(tenantB.id()));
                assertThat(repository.count()).as("count %s", domainType.getSimpleName()).isEqualTo(visible.size());
                assertThat(repository.findById(idInA)).as("findById %s", domainType.getSimpleName()).isEmpty();
                assertThat(repository.existsById(idInA)).as("existsById %s", domainType.getSimpleName()).isFalse();
                return null;
            });
            inTenant(tenantA, () -> {
                assertThat(repository.findById(idInA)).as("own row %s", domainType.getSimpleName()).isPresent();
                return null;
            });
            checked++;
        }
        assertThat(checked).isEqualTo(idsInA.size());
    }

    @Test
    void derivedQueriesAreTenantScoped() {
        inTenant(tenantB, () -> {
            // tenant B also has a branch "001": only its own may be found
            assertThat(branches.findByCode("001")).get().extracting(Branch::getId).isEqualTo(tenantB.branch("001"));
            assertThat(profiles.findByUsername(usernameA)).isEmpty();
            assertThat(roles.findByCode(roleCodeA)).isEmpty();
            assertThat(branchScopes.findByUserId((UUID) idsInA.get(UserProfile.class))).isEmpty();
            assertThat(auditEvents.findByActionOrderByOccurredAt("TEST_EVENT_" + tenantA.code())).isEmpty();
            return null;
        });
    }

    @Test
    void noRepositoryDeletesAnotherTenantsRows() {
        Repositories repositories = new Repositories(applicationContext);
        for (Class<?> domainType : repositories) {
            if (domainType == Tenant.class || domainType == AuditEvent.class || domainType == AuditVerification.class
                    || isFrameworkEntity(domainType) || domainType == DocumentVersion.class) {
                // versions: only drafts can be deleted (database trigger), tested in the document tests
                continue; // audit tables are INSERT/SELECT-only for the application user (AuditGrantsIT)
            }
            @SuppressWarnings("unchecked")
            JpaRepository<Object, Object> repository = (JpaRepository<Object, Object>) repositories
                    .getRepositoryFor(domainType).orElseThrow();
            Object idInA = idsInA.get(domainType);
            inTenant(tenantB, () -> {
                repository.deleteById(idInA);
                repository.deleteAll();
                return null;
            });
            inTenant(tenantA, () -> {
                assertThat(repository.findById(idInA)).as("%s survives", domainType.getSimpleName()).isPresent();
                return null;
            });
        }
    }

    @Test
    void anotherTenantsEntityCannotBeSavedFromThisTenant() {
        Branch branchOfA = inTenant(tenantA, () -> branches.findById(tenantA.branch("001")).orElseThrow());
        branchOfA.rename("Renamed from tenant B");

        assertThatThrownBy(() -> inTenant(tenantB, () -> branches.save(branchOfA))).isInstanceOf(Exception.class);
        assertThat(inTenant(tenantA, () -> branches.findById(tenantA.branch("001")).orElseThrow().getName()))
                .isEqualTo("Branch 001");
    }

    @Test
    void bulkJpqlUpdatesAreTenantScoped() {
        int updated = inTenant(tenantB, () -> entityManager
                .createQuery("update Branch b set b.name = :name")
                .setParameter("name", "Bulk renamed by tenant B")
                .executeUpdate());

        assertThat(updated).isEqualTo(1); // tenant B's own branch only
        assertThat(inTenant(tenantA, () -> branches.findById(tenantA.branch("001")).orElseThrow().getName()))
                .isEqualTo("Branch 001");
    }

    @Test
    void rowsCannotReferenceAnotherTenantsRows() {
        UUID userInB = UUID.randomUUID();
        inTenant(tenantB, () -> profiles.save(profile(userInB, "carol-" + tenantB.code().toLowerCase())));

        // composite foreign key (tenant_id, branch_id): tenant B cannot point at tenant A's branch
        assertThatThrownBy(() -> inTenant(tenantB,
                () -> branchScopes.saveAndFlush(new UserBranchScope(userInB, tenantA.branch("001")))))
                .isInstanceOf(Exception.class);
    }

    @Test
    void withoutATenantNothingIsVisibleOrWritable() {
        List<Branch> visible = transactions.execute(status -> branches.findAll());
        Long profileCount = transactions.execute(status -> profiles.count());
        assertThat(visible).isEmpty();
        assertThat(profileCount).isZero();

        assertThatThrownBy(() -> transactions.execute(status -> branches.saveAndFlush(new Branch("999", "x", null))))
                .isInstanceOf(Exception.class);
    }

    /** Runs work with an authenticated request context (needed by maker-checker, which records the proposer). */
    private static <T> T asUser(CreatedTenant tenant, UUID userId, Supplier<T> work) {
        RequestContext context = new RequestContext(tenant.id(), tenant.code(), userId, "alice", PrincipalType.USER,
                Set.of("TENANT_ADMIN"), Set.of(), BranchScope.ALL, Classification.RESTRICTED, "isolation-test");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(context, null));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private <T> T inTenant(CreatedTenant tenant, Supplier<T> work) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> work.get()));
    }

    private static UserProfile profile(UUID id, String username) {
        return new UserProfile(id, username, username, username + "@example.org", PrincipalType.USER, null,
                BranchScopeType.LIST, Classification.INTERNAL);
    }

    /**
     * Entities of this application. Spring Modulith's event publication registry (the outbox) is framework
     * infrastructure: its rows are not tenant-scoped, are never read by application code, and its event payloads
     * carry the tenant ID (contract records).
     */
    private Set<Class<?>> applicationEntities() {
        return entityManager.getMetamodel().getEntities().stream().map(EntityType::getJavaType)
                .filter(type -> !isFrameworkEntity(type)).collect(Collectors.toSet());
    }

    private static boolean isFrameworkEntity(Class<?> type) {
        return type.getName().startsWith("org.springframework.modulith.");
    }

    private static UUID tenantIdOf(Object entity) {
        for (Class<?> current = entity.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(TenantId.class)) {
                    field.setAccessible(true);
                    try {
                        return (UUID) field.get(entity);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        throw new IllegalStateException("No @TenantId on " + entity.getClass());
    }

    private static boolean hasTenantIdField(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(TenantId.class)) {
                    return true;
                }
            }
        }
        return false;
    }
}
