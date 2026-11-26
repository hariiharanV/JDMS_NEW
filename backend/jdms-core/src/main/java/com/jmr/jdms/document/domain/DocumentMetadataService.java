package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.MetadataChanged;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.shared.security.Roles;
import com.jmr.jdms.workflow.api.ApplyResult;
import com.jmr.jdms.workflow.api.ChangeHandler;
import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.ChangeProposal;
import com.jmr.jdms.workflow.api.ChangeRequests;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Metadata changes (spec §4.3): a JSON merge patch (RFC 7396) over the document's metadata. Applied directly, or —
 * when the document type requires approval for metadata changes — proposed for a checker's approval (maker-checker,
 * ADR-0009). The audit event records before and after per changed field, with sensitive custom fields masked.
 */
@Service
public class DocumentMetadataService implements ChangeHandler {

    public static final String SUBJECT = "DOCUMENT_METADATA";
    public static final String DOCUMENT_PENDING_APPROVAL = "DOCUMENT_PENDING_APPROVAL";
    static final String MASK = "[sensitive]";

    private final DocumentRepository documents;
    private final DocumentAccess access;
    private final DocumentViews views;
    private final MetadataCatalog catalog;
    private final MetadataResolver resolver;
    private final ChangeRequests changeRequests;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final JsonMapper jsonMapper;

    DocumentMetadataService(DocumentRepository documents, DocumentAccess access, DocumentViews views,
            MetadataCatalog catalog, MetadataResolver resolver, ChangeRequests changeRequests, AuditTrail auditTrail,
            ApplicationEventPublisher events, JsonMapper jsonMapper) {
        this.documents = documents;
        this.access = access;
        this.views = views;
        this.catalog = catalog;
        this.resolver = resolver;
        this.changeRequests = changeRequests;
        this.auditTrail = auditTrail;
        this.events = events;
        this.jsonMapper = jsonMapper;
    }

    /** Applied change (document) or proposal (pending change). */
    public record ChangeOutcome(DocumentView document, PendingChangeView pendingChange) {
    }

    @Transactional
    public ChangeOutcome change(UUID documentId, long expectedVersion, JsonNode mergePatch) {
        Document document = access.require(Action.EDIT_METADATA, documents.findById(documentId).orElse(null));
        if (document.getVersionLock() != expectedVersion) {
            throw ApiException.staleVersion();
        }
        requireEditable(document);
        DocumentFieldsInput before = views.fieldsOf(document);
        DocumentFieldsInput after = merge(before, mergePatch);
        DocumentTypeInfo type = catalog.findDocumentType(document.getDocumentTypeId()).orElseThrow();
        DocumentMetadata metadata = resolver.resolve(type, after, null);

        // A returned upload (DRAFT) is amended directly: it goes through approval again when resubmitted
        if (type.metaChangeNeedsApproval() && document.getStatus() == DocumentStatus.ACTIVE) {
            PendingChangeView pending = changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE,
                    documentId, null, expectedVersion, after, "Change metadata of document \"" + document.getTitle()
                            + "\" (" + String.join(", ", changedFields(before, after)) + ")"));
            return new ChangeOutcome(null, pending);
        }
        apply(document, type, before, after, metadata, null);
        return new ChangeOutcome(views.view(document), null);
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Set<String> candidateRoles() {
        return Set.of(Roles.CHECKER);
    }

    @Override
    public String historyType() {
        return "METADATA_CHANGE";
    }

    @Override
    public UUID documentId(PendingChangeView change) {
        return change.subjectId();
    }

    /** Checkers approve metadata changes of documents they may approve ({@code APPROVE} on the document). */
    @Override
    public boolean canDecide(PendingChangeView change) {
        return documents.findById(change.subjectId()).map(document -> access.can(Action.APPROVE, document))
                .orElse(false);
    }

    @Override
    public ApplyResult apply(PendingChangeView change) {
        Document document = documents.findById(change.subjectId()).orElse(null);
        if (document == null || document.getVersionLock() != change.baseVersion()) {
            return ApplyResult.stale("The document was changed after this change was proposed.");
        }
        if (document.getStatus() != DocumentStatus.ACTIVE) {
            return ApplyResult.stale("The document is no longer active.");
        }
        if (document.isCheckedOut() && !document.isCheckedOutBy(change.proposedBy())) {
            return ApplyResult.stale("The document is now checked out by another user.");
        }
        DocumentTypeInfo type = catalog.findDocumentType(document.getDocumentTypeId()).orElseThrow();
        DocumentFieldsInput after = jsonMapper.treeToValue(change.payload(), DocumentFieldsInput.class);
        DocumentMetadata metadata;
        try {
            metadata = resolver.resolve(type, after, null);
        } catch (ValidationFailedException e) {
            return ApplyResult.stale("The proposed metadata is no longer valid: " + e.violations().stream()
                    .map(Violation::field).toList());
        }
        apply(document, type, views.fieldsOf(document), after, metadata, change);
        return ApplyResult.ok();
    }

    private void apply(Document document, DocumentTypeInfo type, DocumentFieldsInput before,
            DocumentFieldsInput after, DocumentMetadata metadata, PendingChangeView change) {
        document.apply(metadata);
        documents.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        if (change != null) {
            details.put("changeId", change.id().toString());
            details.put("proposedBy", change.proposedBy().toString());
        }
        details.put("changes", diff(before, after, type.sensitiveFields()));
        auditTrail.record(AuditEntry.success(DocumentAuditActions.METADATA_CHANGED,
                DocumentAuditActions.RESOURCE_DOCUMENT, document.getId(), details));
        events.publishEvent(new MetadataChanged(RequestContext.require().tenantId(), document.getId()));
    }

    /** Active documents, and returned uploads (drafts) being amended; never while a checker is deciding. */
    private void requireEditable(Document document) {
        if (document.getStatus() == DocumentStatus.PENDING_APPROVAL) {
            throw new ApiException(HttpStatus.CONFLICT, DOCUMENT_PENDING_APPROVAL,
                    "The document is waiting for approval; it can be changed only if it is returned.");
        }
        if (document.getStatus() != DocumentStatus.ACTIVE && document.getStatus() != DocumentStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, DocumentVersioningService.DOCUMENT_NOT_ACTIVE,
                    "Rejected, deleted or disposed documents cannot be changed.");
        }
        if (document.isCheckedOut() && !document.isCheckedOutBy(RequestContext.require().subjectId())) {
            throw new ApiException(HttpStatus.CONFLICT, DocumentVersioningService.DOCUMENT_CHECKED_OUT,
                    "The document is checked out by another user.");
        }
    }

    /** RFC 7396: members replace, {@code null} removes, objects merge recursively. Type and cabinet are fixed. */
    private DocumentFieldsInput merge(DocumentFieldsInput current, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw ApiException.unprocessable("PATCH_INVALID", "The body must be a JSON object (merge patch).");
        }
        for (String fixed : List.of("cabinetCode", "documentTypeCode")) {
            JsonNode value = patch.get(fixed);
            if (value != null && !value.isNull() && !Objects.equals(value.asString(), fixed.equals("cabinetCode")
                    ? current.cabinetCode() : current.documentTypeCode())) {
                throw new ValidationFailedException("The document metadata is invalid.",
                        List.of(new Violation(fixed, "FIELD_IMMUTABLE", "Cannot be changed after upload.")));
            }
        }
        ObjectNode target = jsonMapper.valueToTree(current);
        mergeInto(target, (ObjectNode) patch);
        return jsonMapper.treeToValue(target, DocumentFieldsInput.class);
    }

    private static void mergeInto(ObjectNode target, ObjectNode patch) {
        for (var entry : patch.properties()) {
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                target.remove(entry.getKey());
            } else if (value.isObject() && target.get(entry.getKey()) instanceof ObjectNode existing) {
                mergeInto(existing, (ObjectNode) value);
            } else {
                target.set(entry.getKey(), value);
            }
        }
    }

    private Map<String, Object> diff(DocumentFieldsInput before, DocumentFieldsInput after, Set<String> sensitive) {
        ObjectNode a = jsonMapper.valueToTree(before);
        ObjectNode b = jsonMapper.valueToTree(after);
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String field : new TreeSet<>(union(a, b))) {
            if (field.equals("customFields")) {
                continue;
            }
            if (!Objects.equals(a.get(field), b.get(field))) {
                changes.put(field, Map.of("before", text(a.get(field)), "after", text(b.get(field))));
            }
        }
        ObjectNode ca = a.get("customFields") instanceof ObjectNode node ? node : jsonMapper.createObjectNode();
        ObjectNode cb = b.get("customFields") instanceof ObjectNode node ? node : jsonMapper.createObjectNode();
        for (String key : new TreeSet<>(union(ca, cb))) {
            if (!Objects.equals(ca.get(key), cb.get(key))) {
                boolean masked = sensitive.contains(key);
                changes.put("customFields." + key, Map.of("before", masked ? MASK : text(ca.get(key)),
                        "after", masked ? MASK : text(cb.get(key))));
            }
        }
        return changes;
    }

    private List<String> changedFields(DocumentFieldsInput before, DocumentFieldsInput after) {
        return List.copyOf(diff(before, after, Set.of()).keySet());
    }

    private static Set<String> union(ObjectNode a, ObjectNode b) {
        Set<String> names = new TreeSet<>(a.propertyNames());
        names.addAll(b.propertyNames());
        return names;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.isString() ? node.asString() : node.toString();
    }
}
