package com.jmr.jdms.metadata.domain;

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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces a document type's ordered list of fields as one change (PUT on the collection). Fields are matched by
 * key: kept keys are updated, new keys added, missing keys removed. Existing documents keep their stored values;
 * removed fields are no longer shown or validated. Applying the change increments the type's version.
 */
@Service
public class FieldListAdministration implements ChangeHandler {

    public static final String SUBJECT = "DOCUMENT_TYPE_FIELDS";

    private final DocumentTypeAdministration documentTypes;
    private final FieldDefinitionRepository fields;
    private final ChangeRequests changeRequests;
    private final MetadataChangeSupport support;
    private final EntityManager entityManager;

    FieldListAdministration(DocumentTypeAdministration documentTypes, FieldDefinitionRepository fields,
            ChangeRequests changeRequests, MetadataChangeSupport support, EntityManager entityManager) {
        this.documentTypes = documentTypes;
        this.fields = fields;
        this.changeRequests = changeRequests;
        this.support = support;
        this.entityManager = entityManager;
    }

    public record FieldListPayload(List<FieldSpec> fields) {
    }

    @Transactional(readOnly = true)
    public List<FieldSpec> list(UUID documentTypeId) {
        documentTypes.load(documentTypeId);
        return documentTypes.fieldsOf(documentTypeId);
    }

    @Transactional
    public PendingChangeView proposeReplace(UUID documentTypeId, long expectedVersion, List<FieldSpec> proposed) {
        DocumentType type = documentTypes.loadAtVersion(documentTypeId, expectedVersion);
        List<FieldSpec> specs = proposed == null ? List.of()
                : proposed.stream().map(FieldDefinitionRules::withDefaults).toList();
        List<Violation> violations = FieldDefinitionRules.check(specs, documentTypes.lovLookup(specs));
        ValidationFailedException.throwIfAny("The fields are invalid.", violations);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE, documentTypeId, null,
                expectedVersion, new FieldListPayload(specs), "Replace fields of document type " + type.getCode()
                        + " (" + summary(documentTypes.fieldsOf(documentTypeId), specs) + ")"));
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Set<String> candidateRoles() {
        return Set.of(Roles.TENANT_ADMIN);
    }

    @Override
    public boolean canDecide(PendingChangeView change) {
        return support.canAdministerMetadata();
    }

    @Override
    public ApplyResult apply(PendingChangeView change) {
        DocumentType type;
        try {
            type = documentTypes.load(change.subjectId());
        } catch (RuntimeException notFound) {
            return ApplyResult.stale("The document type no longer exists.");
        }
        if (type.getVersionLock() != change.baseVersion()) {
            return ApplyResult.stale("The document type was changed after this change was proposed.");
        }
        List<FieldSpec> specs = support.payload(change, FieldListPayload.class).fields();
        List<Violation> problems = FieldDefinitionRules.check(specs, documentTypes.lovLookup(specs));
        if (!problems.isEmpty()) {
            return ApplyResult.stale("Fields are no longer valid: " + MetadataChangeSupport.describe(problems));
        }

        Map<String, FieldDefinition> existing = fields.findByDocumentTypeId(type.getId()).stream()
                .collect(Collectors.toMap(FieldDefinition::getFieldKey, Function.identity()));
        Set<String> wanted = specs.stream().map(FieldSpec::key).collect(Collectors.toSet());
        existing.values().stream().filter(field -> !wanted.contains(field.getFieldKey())).forEach(fields::delete);
        for (FieldSpec spec : specs) {
            FieldDefinition field = existing.get(spec.key());
            if (field == null) {
                fields.save(new FieldDefinition(Ids.newId(), type.getId(), spec));
            } else {
                field.update(spec);
            }
        }
        // the field list is part of the type: concurrent changes to the type must see a new version
        entityManager.lock(type, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

        support.auditApplied("DOCUMENT_TYPE_FIELDS_REPLACED", DocumentTypeAdministration.SUBJECT, type.getId(),
                change, Map.of("code", type.getCode(), "fields", specs.stream().map(FieldSpec::key).toList()));
        return ApplyResult.ok();
    }

    private static String summary(List<FieldSpec> before, List<FieldSpec> after) {
        Set<String> beforeKeys = before.stream().map(FieldSpec::key).collect(Collectors.toCollection(TreeSet::new));
        Set<String> afterKeys = after.stream().map(FieldSpec::key).collect(Collectors.toCollection(TreeSet::new));
        Set<String> added = new TreeSet<>(afterKeys);
        added.removeAll(beforeKeys);
        Set<String> removed = new TreeSet<>(beforeKeys);
        removed.removeAll(afterKeys);
        return "added " + (added.isEmpty() ? "none" : String.join(", ", added)) + "; removed "
                + (removed.isEmpty() ? "none" : String.join(", ", removed));
    }
}
