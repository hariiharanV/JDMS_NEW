package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.AccessProvisioning;
import com.jmr.jdms.access.api.AccessProvisioning.ImportedRule;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.DocumentCreated;
import com.jmr.jdms.contracts.VersionCreated;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.BranchScope;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ErrorCodes;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.tenant.api.BranchDirectory;
import com.jmr.jdms.tenant.api.BranchInfo;
import com.jmr.jdms.workflow.api.Approvals;
import com.jmr.jdms.workflow.api.Approvals.ImportedDecision;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports one legacy JDMS document (ADR-0020): validated like an upload, every version stored as a new encrypted
 * blob and checked against the checksum the migration tool computed from the decrypted legacy file, then the
 * document, its versions, approval history and per-document role permissions are created in one transaction.
 * Idempotent by legacy document ID. Callers hold {@code IMPORT}.
 */
@Service
public class DocumentImportService {

    public static final String SOURCE = "MIGRATION";
    public static final String CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH";
    public static final String VERSION_FILES_MISMATCH = "VERSION_FILES_MISMATCH";
    static final int MAX_VERSIONS = 100;
    /** Document actions a legacy permission can map to; administration and records actions never come from it. */
    static final Set<Action> IMPORTABLE_ACTIONS = EnumSet.of(Action.VIEW_METADATA, Action.VIEW_CONTENT,
            Action.DOWNLOAD, Action.EDIT_METADATA, Action.CHECK_OUT, Action.DELETE);
    /** Actions reported by the access check: what a document's reader can be allowed to do. */
    static final List<Action> CHECKED_ACTIONS = List.of(Action.VIEW_METADATA, Action.VIEW_CONTENT, Action.DOWNLOAD,
            Action.EDIT_METADATA, Action.CHECK_OUT, Action.DELETE);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

    /** One version as the legacy system holds it; files arrive in this order, oldest first. */
    public record ImportedVersion(String fileName, String mimeType, String sha256, Instant createdAt, String createdBy,
            String comment) {
    }

    /** {@code status}: {@code ACTIVE}, or {@code DELETED} for documents in the legacy delete folder. */
    public record ImportRequest(String legacyDocumentId, DocumentFieldsInput fields, String status,
            Instant createdAt, String createdBy, List<ImportedVersion> versions, List<ImportedDecision> history,
            List<ImportedRule> permissions) {

        public ImportRequest {
            versions = versions == null ? List.of() : List.copyOf(versions);
            history = history == null ? List.of() : List.copyOf(history);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    public record StoredVersion(String number, UUID versionId, String sha256, long sizeBytes) {
    }

    /**
     * What the core holds for the legacy document; {@code alreadyImported} when an earlier run created it ({@code rules}
     * is then not reported).
     */
    public record ImportResult(UUID documentId, String legacyDocumentId, DocumentStatus status,
            List<StoredVersion> versions, int historyRecords, Integer rules, boolean alreadyImported) {
    }

    /** A validated request whose files are being stored; {@link #complete} or {@link #abandon} ends it. */
    public final class Import {

        private final ImportRequest request;
        private final DocumentTypeInfo type;
        private final DocumentMetadata metadata;
        private final ImportResult existing;
        private final List<StoredContent> stored = new ArrayList<>();

        private Import(ImportRequest request, DocumentTypeInfo type, DocumentMetadata metadata, ImportResult existing) {
            this.request = request;
            this.type = type;
            this.metadata = metadata;
            this.existing = existing;
        }

        /** False when an earlier run imported the document: its files need not be sent or read. */
        public boolean wantsFiles() {
            return existing == null;
        }

        /** Stores the next version's file and checks it against the declared checksum. */
        public void store(String fileName, InputStream content) {
            int index = stored.size();
            if (index >= request.versions().size()) {
                abandon();
                throw ApiException.unprocessable(VERSION_FILES_MISMATCH,
                        "More files than declared versions were sent.");
            }
            StoredContent file = contentWriter.store(content, type.maxFileSizeMb());
            stored.add(file);
            if (!file.sha256().equals(request.versions().get(index).sha256())) {
                abandon();
                throw ApiException.unprocessable(CHECKSUM_MISMATCH, "Version " + (index + 1)
                        + " does not match its declared SHA-256: the file changed after it was decrypted.");
            }
        }

        public ImportResult complete() {
            if (existing != null) {
                return existing;
            }
            if (stored.size() != request.versions().size()) {
                abandon();
                throw ApiException.unprocessable(VERSION_FILES_MISMATCH, "Declared " + request.versions().size()
                        + " versions but received " + stored.size() + " files.");
            }
            try {
                return transactions.execute(status -> create(this));
            } catch (DataIntegrityViolationException concurrentImport) {
                abandon();
                return transactions.execute(status -> documents.findByLegacyDocumentId(request.legacyDocumentId())
                        .map(DocumentImportService.this::resultOf).orElseThrow(() -> concurrentImport));
            } catch (RuntimeException e) {
                abandon();
                throw e;
            }
        }

        /** Removes the blobs stored so far (the import failed or was refused). */
        public void abandon() {
            stored.forEach(contentWriter::discard);
            stored.clear();
        }
    }

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final MetadataResolver resolver;
    private final ContentWriter contentWriter;
    private final DocumentAccess access;
    private final AccessPolicy policy;
    private final AccessProvisioning provisioning;
    private final Approvals approvals;
    private final BranchDirectory branches;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final CabinetUsages usages;

    DocumentImportService(DocumentRepository documents, DocumentVersionRepository versions, MetadataResolver resolver,
            ContentWriter contentWriter, DocumentAccess access, AccessPolicy policy, AccessProvisioning provisioning,
            Approvals approvals, BranchDirectory branches, AuditTrail auditTrail, ApplicationEventPublisher events,
            TransactionTemplate transactions, CabinetUsages usages) {
        this.documents = documents;
        this.versions = versions;
        this.resolver = resolver;
        this.contentWriter = contentWriter;
        this.access = access;
        this.policy = policy;
        this.provisioning = provisioning;
        this.approvals = approvals;
        this.branches = branches;
        this.auditTrail = auditTrail;
        this.events = events;
        this.transactions = transactions;
        this.usages = usages;
    }

    /** Validates the request (or finds the earlier import) before any file is read. */
    public Import start(ImportRequest request) {
        requireImport();
        List<Violation> violations = new ArrayList<>();
        String legacyId = request.legacyDocumentId();
        if (legacyId == null || legacyId.isBlank() || legacyId.length() > 100) {
            throw new ValidationFailedException("The import is invalid.", List.of(new Violation("legacyDocumentId",
                    "LEGACY_ID_REQUIRED", "A legacy document ID of at most 100 characters is required.")));
        }
        ImportResult existing = transactions.execute(status -> documents.findByLegacyDocumentId(legacyId)
                .map(this::resultOf).orElse(null));
        if (existing != null) {
            return new Import(request, null, null, existing);
        }
        if (!"ACTIVE".equals(request.status()) && !"DELETED".equals(request.status())) {
            violations.add(new Violation("status", "STATUS_INVALID", "The status must be ACTIVE or DELETED."));
        }
        if (request.versions().isEmpty() || request.versions().size() > MAX_VERSIONS) {
            violations.add(new Violation("versions", "VERSIONS_INVALID",
                    "Between 1 and " + MAX_VERSIONS + " versions are required."));
        }
        for (int i = 0; i < request.versions().size(); i++) {
            String sha = request.versions().get(i).sha256();
            if (sha == null || !SHA256.matcher(sha).matches()) {
                violations.add(new Violation("versions[" + i + "].sha256", "SHA256_INVALID",
                        "A lower-case hex SHA-256 is required."));
            }
        }
        for (int i = 0; i < request.history().size(); i++) {
            ImportedDecision decision = request.history().get(i);
            if (decision.decision() == null || decision.decidedAt() == null || decision.actorName() == null
                    || decision.actorName().isBlank() || decision.actorName().length() > 200) {
                violations.add(new Violation("history[" + i + "]", "HISTORY_INVALID",
                        "Each step needs a decision, a time and an actor name (at most 200 characters)."));
            }
        }
        for (int i = 0; i < request.permissions().size(); i++) {
            ImportedRule rule = request.permissions().get(i);
            if (rule.role() == null || !ROLE.matcher(rule.role()).matches() || rule.actions().isEmpty()
                    || !IMPORTABLE_ACTIONS.containsAll(rule.actions())) {
                violations.add(new Violation("permissions[" + i + "]", "PERMISSION_INVALID",
                        "A JDMS role code and document actions (" + IMPORTABLE_ACTIONS + ") are required."));
            }
        }
        DocumentTypeInfo type = null;
        DocumentMetadata metadata = null;
        try {
            type = resolver.uploadableType(request.fields());
            metadata = resolver.resolve(type, request.fields(), request.versions().isEmpty() ? legacyId
                    : ClientFileInfo.fileName(request.versions().getLast().fileName()));
        } catch (ValidationFailedException invalid) {
            violations.addAll(invalid.violations());
        }
        ValidationFailedException.throwIfAny("The import is invalid.", violations);
        return new Import(request, type, metadata, null);
    }

    private ImportResult create(Import run) {
        ImportRequest request = run.request;
        DocumentTypeInfo type = run.type;
        Document fresh = new Document(type.cabinetId(), type.id(), DocumentStatus.ACTIVE, SOURCE, run.metadata);
        fresh.importedFrom(request.legacyDocumentId(), micros(request.createdAt()), request.createdBy());
        // assigned IDs make save a merge: work on the managed copy it returns
        Document document = documents.save(fresh);
        List<DocumentVersion> created = new ArrayList<>();
        for (int i = 0; i < run.stored.size(); i++) {
            ImportedVersion declared = request.versions().get(i);
            DocumentVersion version = new DocumentVersion(null, document.getId(), i + 1, 0, ApprovalStatus.APPROVED,
                    run.stored.get(i), ClientFileInfo.mimeType(declared.mimeType()),
                    ClientFileInfo.fileName(declared.fileName()), comment(declared.comment()));
            version.importedFrom(micros(declared.createdAt()), declared.createdBy());
            created.add(versions.save(version));
        }
        usages.add(document.getCabinetId(), run.stored.stream().mapToLong(StoredContent::sizeBytes).sum());
        DocumentVersion current = created.getLast();
        document.makeCurrent(current.getId());
        if ("DELETED".equals(request.status())) {
            document.moveToRecycleBin(RequestContext.require().subjectId(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
        documents.flush();
        int history = approvals.importHistory(document.getId(), current.getId(), request.history());
        int rules = provisioning.importDocumentRules(document.getId(), request.permissions());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("legacyDocumentId", request.legacyDocumentId());
        details.put("documentTypeCode", type.code());
        details.put("versions", created.size());
        details.put("sha256", created.stream().map(DocumentVersion::getSha256).toList());
        details.put("historyRecords", history);
        details.put("rules", rules);
        details.put("status", document.getStatus().name());
        auditTrail.record(AuditEntry.success("DOCUMENT_IMPORTED", DocumentAuditActions.RESOURCE_DOCUMENT,
                document.getId(), details));
        UUID tenantId = RequestContext.require().tenantId();
        events.publishEvent(new DocumentCreated(tenantId, document.getId(), current.getId()));
        // every version goes through antivirus and processing like an upload: legacy files are untrusted too
        created.forEach(version -> events.publishEvent(new VersionCreated(tenantId, document.getId(),
                version.getId())));
        return new ImportResult(document.getId(), request.legacyDocumentId(), document.getStatus(),
                created.stream().map(DocumentImportService::stored).toList(), history, rules, false);
    }

    /**
     * The document actions a user with these roles, branches and clearance would have on each document, for the
     * migration's permission comparison. Decided by the access policy, without audit; documents of another tenant
     * or unknown IDs are left out.
     */
    public Map<UUID, List<Action>> accessCheck(Set<String> roles, boolean allBranches, Set<String> branchCodes,
            Classification clearance, List<UUID> documentIds) {
        requireImport();
        Set<UUID> branchIds = allBranches ? Set.of() : Set.copyOf(branches.findByCodes(branchCodes).stream()
                .map(BranchInfo::id).toList());
        Subject subject = new Subject(RequestContext.require().tenantId(), UUID.randomUUID(), roles,
                allBranches ? BranchScope.ALL : new BranchScope(false, branchIds), clearance);
        Map<UUID, List<Action>> result = new LinkedHashMap<>();
        transactions.executeWithoutResult(status -> documents.findAllById(documentIds).forEach(document -> {
            ResourceRef.Document resource = access.resourceOf(document);
            result.put(document.getId(), CHECKED_ACTIONS.stream()
                    .filter(action -> policy.decide(subject, action, resource).allowed()).toList());
        }));
        return result;
    }

    private void requireImport() {
        RequestContext caller = RequestContext.require();
        if (!policy.check(Action.IMPORT, new ResourceRef.Tenant(caller.tenantId())).allowed()) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED, "Importing legacy documents is not allowed.");
        }
    }

    private ImportResult resultOf(Document document) {
        List<DocumentVersion> stored = versions.findByDocumentIdOrderByMajorDescMinorDesc(document.getId()).reversed();
        return new ImportResult(document.getId(), document.getLegacyDocumentId(), document.getStatus(),
                stored.stream().map(DocumentImportService::stored).toList(),
                approvals.history(document.getId()).size(), null, true);
    }

    private static StoredVersion stored(DocumentVersion version) {
        return new StoredVersion(version.number(), version.getId(), version.getSha256(), version.getSizeBytes());
    }

    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static String comment(String comment) {
        return comment == null || comment.isBlank() ? null : comment.length() > 1000 ? comment.substring(0, 1000)
                : comment;
    }
}
