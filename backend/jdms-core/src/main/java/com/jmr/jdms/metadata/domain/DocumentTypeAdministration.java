package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.metadata.api.StandardFields;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.error.ApiException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document types: reads, proposals and applying approved changes. A new type is proposed together with its fields
 * (one approval); later field changes go through {@link FieldListAdministration}.
 */
@Service
public class DocumentTypeAdministration implements ChangeHandler {

    public static final String SUBJECT = "DOCUMENT_TYPE";
    public static final String DOCUMENT_TYPE_NOT_FOUND = "DOCUMENT_TYPE_NOT_FOUND";

    private final DocumentTypeRepository documentTypes;
    private final CabinetRepository cabinets;
    private final FieldDefinitionRepository fields;
    private final LovCatalog lovCatalog;
    private final ChangeRequests changeRequests;
    private final MetadataChangeSupport support;

    DocumentTypeAdministration(DocumentTypeRepository documentTypes, CabinetRepository cabinets,
            FieldDefinitionRepository fields, LovCatalog lovCatalog, ChangeRequests changeRequests,
            MetadataChangeSupport support) {
        this.documentTypes = documentTypes;
        this.cabinets = cabinets;
        this.fields = fields;
        this.lovCatalog = lovCatalog;
        this.changeRequests = changeRequests;
        this.support = support;
    }

    /**
     * Proposed document type. {@code cabinetId} and {@code code} are fixed after creation; {@code fields} is used
     * only on create; {@code status} only on update.
     */
    public record DocumentTypePayload(UUID cabinetId, String code, String nameEn, String nameAr, String category,
            Set<UploadChannel> channels, Classification defaultClassification, String processKey,
            boolean metaChangeNeedsApproval, boolean deleteRequiresApproval, boolean declareRecordOnApproval,
            Set<String> requiredStandardFields, MetadataStatus status, List<FieldSpec> fields) {

        DocumentTypeAttributes attributes() {
            return new DocumentTypeAttributes(nameEn, nameAr, category, channels, defaultClassification, processKey,
                    metaChangeNeedsApproval, deleteRequiresApproval, declareRecordOnApproval, requiredStandardFields);
        }
    }

    public record DocumentTypeView(UUID id, UUID cabinetId, String code, String nameEn, String nameAr,
            String category, List<String> channels, Classification defaultClassification, String processKey,
            boolean metaChangeNeedsApproval, boolean deleteRequiresApproval, boolean declareRecordOnApproval,
            List<String> requiredStandardFields, MetadataStatus status, long version, List<FieldSpec> fields) {

        static DocumentTypeView of(DocumentType type, List<FieldSpec> fields) {
            return new DocumentTypeView(type.getId(), type.getCabinetId(), type.getCode(), type.getNameEn(),
                    type.getNameAr(), type.getCategory(), type.getChannels(), type.getDefaultClassification(),
                    type.getProcessKey(), type.isMetaChangeNeedsApproval(), type.isDeleteRequiresApproval(),
                    type.isDeclareRecordOnApproval(), type.getRequiredStandardFields(), type.getStatus(),
                    type.getVersionLock(), fields);
        }
    }

    @Transactional(readOnly = true)
    public Page<DocumentTypeView> list(UUID cabinetId, Pageable pageable) {
        Page<DocumentType> page = cabinetId == null ? documentTypes.findAll(pageable)
                : documentTypes.findByCabinetId(cabinetId, pageable);
        return page.map(type -> DocumentTypeView.of(type, null));
    }

    @Transactional(readOnly = true)
    public DocumentTypeView get(UUID id) {
        DocumentType type = load(id);
        return DocumentTypeView.of(type, fieldsOf(id));
    }

    @Transactional
    public PendingChangeView proposeCreate(DocumentTypePayload payload) {
        List<FieldSpec> specs = payload.fields() == null ? List.of()
                : payload.fields().stream().map(FieldDefinitionRules::withDefaults).toList();
        List<Violation> violations = attributeViolations(payload, true);
        violations.addAll(FieldDefinitionRules.check(specs, lovLookup(specs)));
        ValidationFailedException.throwIfAny("The document type is invalid.", violations);
        if (documentTypes.existsByCode(payload.code())) {
            throw MetadataChangeSupport.codeInUse("document type");
        }
        Cabinet cabinet = activeCabinet(payload.cabinetId());
        DocumentTypePayload normalised = new DocumentTypePayload(cabinet.getId(), payload.code(),
                payload.nameEn().trim(), MetadataChangeSupport.optional(payload.nameAr()), payload.category().trim(),
                payload.channels(),
                payload.defaultClassification(), payload.processKey(), payload.metaChangeNeedsApproval(),
                payload.deleteRequiresApproval(), payload.declareRecordOnApproval(),
                safe(payload.requiredStandardFields()), MetadataStatus.ACTIVE, specs);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.CREATE, Ids.newId(),
                payload.code(), null, normalised, "Create document type " + payload.code() + " in cabinet "
                        + cabinet.getCode() + " with " + specs.size() + " field(s)"));
    }

    @Transactional
    public PendingChangeView proposeUpdate(UUID id, long expectedVersion, DocumentTypePayload payload) {
        DocumentType type = loadAtVersion(id, expectedVersion);
        List<Violation> violations = attributeViolations(payload, false);
        if (payload.code() != null && !payload.code().equals(type.getCode())) {
            violations.add(new Violation("code", "CODE_IMMUTABLE", "The code cannot be changed."));
        }
        if (payload.cabinetId() != null && !payload.cabinetId().equals(type.getCabinetId())) {
            violations.add(new Violation("cabinetId", "CABINET_IMMUTABLE", "The cabinet cannot be changed."));
        }
        if (payload.fields() != null) {
            violations.add(new Violation("fields", "FIELDS_SEPARATE",
                    "Change fields through PUT /admin/document-types/{id}/fields."));
        }
        ValidationFailedException.throwIfAny("The document type is invalid.", violations);
        DocumentTypePayload normalised = new DocumentTypePayload(type.getCabinetId(), type.getCode(),
                payload.nameEn().trim(), MetadataChangeSupport.optional(payload.nameAr()), payload.category().trim(),
                payload.channels(),
                payload.defaultClassification(), payload.processKey(), payload.metaChangeNeedsApproval(),
                payload.deleteRequiresApproval(), payload.declareRecordOnApproval(),
                safe(payload.requiredStandardFields()), payload.status(), null);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE, id, null, expectedVersion,
                normalised, "Update document type " + type.getCode()));
    }

    @Transactional
    public PendingChangeView proposeDeactivate(UUID id, long expectedVersion) {
        DocumentType type = loadAtVersion(id, expectedVersion);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.DEACTIVATE, id, null,
                expectedVersion, Map.of(), "Deactivate document type " + type.getCode()));
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
        if (change.operation() == ChangeOperation.CREATE) {
            return applyCreate(change);
        }
        DocumentType type = documentTypes.findById(change.subjectId()).orElse(null);
        if (type == null || type.getVersionLock() != change.baseVersion()) {
            return ApplyResult.stale("The document type was changed after this change was proposed.");
        }
        if (change.operation() == ChangeOperation.DEACTIVATE) {
            type.deactivate();
            support.auditApplied("DOCUMENT_TYPE_DEACTIVATED", SUBJECT, type.getId(), change,
                    Map.of("code", type.getCode()));
            return ApplyResult.ok();
        }
        DocumentTypePayload payload = support.payload(change, DocumentTypePayload.class);
        type.update(payload.attributes(), payload.status());
        support.auditApplied("DOCUMENT_TYPE_UPDATED", SUBJECT, type.getId(), change, Map.of("code", type.getCode()));
        return ApplyResult.ok();
    }

    private ApplyResult applyCreate(PendingChangeView change) {
        DocumentTypePayload payload = support.payload(change, DocumentTypePayload.class);
        if (documentTypes.existsByCode(payload.code())) {
            return ApplyResult.stale("A document type with code " + payload.code() + " was created in the meantime.");
        }
        Cabinet cabinet = cabinets.findById(payload.cabinetId()).orElse(null);
        if (cabinet == null || cabinet.getStatus() != MetadataStatus.ACTIVE) {
            return ApplyResult.stale("The cabinet is no longer active.");
        }
        List<Violation> fieldProblems = FieldDefinitionRules.check(payload.fields(), lovLookup(payload.fields()));
        if (!fieldProblems.isEmpty()) {
            return ApplyResult.stale("Fields are no longer valid: " + MetadataChangeSupport.describe(fieldProblems));
        }
        DocumentType type = documentTypes.save(new DocumentType(change.subjectId(), payload.cabinetId(),
                payload.code(), payload.attributes()));
        payload.fields().forEach(spec -> fields.save(new FieldDefinition(Ids.newId(), type.getId(), spec)));
        support.auditApplied("DOCUMENT_TYPE_CREATED", SUBJECT, type.getId(), change,
                Map.of("code", type.getCode(), "fields", payload.fields().stream().map(FieldSpec::key).toList()));
        return ApplyResult.ok();
    }

    List<FieldSpec> fieldsOf(UUID documentTypeId) {
        return fields.findByDocumentTypeId(documentTypeId).stream().map(FieldDefinition::spec)
                .sorted(FieldSpec.DISPLAY_ORDER).toList();
    }

    /** Active values by LOV code for the LOVs the fields use; null for LOVs that do not exist. */
    java.util.function.Function<String, Set<String>> lovLookup(List<FieldSpec> specs) {
        Map<String, Set<String>> values = lovCatalog.activeValues(specs.stream().map(FieldSpec::lovCode)
                .filter(Objects::nonNull).toList());
        return values::get;
    }

    DocumentType load(UUID id) {
        return documentTypes.findById(id).orElseThrow(() -> ApiException.notFound(DOCUMENT_TYPE_NOT_FOUND,
                "Document type not found."));
    }

    DocumentType loadAtVersion(UUID id, long expectedVersion) {
        DocumentType type = load(id);
        if (type.getVersionLock() != expectedVersion) {
            throw ApiException.staleVersion();
        }
        return type;
    }

    private Cabinet activeCabinet(UUID cabinetId) {
        Cabinet cabinet = cabinetId == null ? null : cabinets.findById(cabinetId).orElse(null);
        if (cabinet == null) {
            throw ApiException.notFound(CabinetAdministration.CABINET_NOT_FOUND, "Cabinet not found.");
        }
        if (cabinet.getStatus() != MetadataStatus.ACTIVE) {
            throw ApiException.unprocessable("CABINET_INACTIVE", "The cabinet is not active.");
        }
        return cabinet;
    }

    private static List<Violation> attributeViolations(DocumentTypePayload payload, boolean create) {
        List<Violation> violations = new ArrayList<>();
        if (create) {
            MetadataChangeSupport.checkCode(payload.code(), violations);
            if (payload.cabinetId() == null) {
                violations.add(new Violation("cabinetId", "CABINET_REQUIRED", "Cabinet is required."));
            }
        } else if (payload.status() == null) {
            violations.add(new Violation("status", "STATUS_REQUIRED", "Status is required."));
        }
        MetadataChangeSupport.checkName("nameEn", payload.nameEn(), 200, violations);
        MetadataChangeSupport.checkOptionalName("nameAr", payload.nameAr(), 200, violations);
        MetadataChangeSupport.checkName("category", payload.category(), 50, violations);
        if (payload.channels() == null || payload.channels().isEmpty()) {
            violations.add(new Violation("channels", "CHANNELS_REQUIRED", "At least one upload channel is required."));
        }
        if (payload.defaultClassification() == null) {
            violations.add(new Violation("defaultClassification", "CLASSIFICATION_REQUIRED",
                    "Default classification is required."));
        }
        MetadataChangeSupport.checkProcessKey("processKey", payload.processKey(), violations);
        safe(payload.requiredStandardFields()).stream().filter(field -> !StandardFields.ALL.contains(field)).sorted()
                .forEach(field -> violations.add(new Violation("requiredStandardFields", "STANDARD_FIELD_UNKNOWN",
                        "Not a standard field: " + field)));
        return violations;
    }

    private static Set<String> safe(Set<String> values) {
        return values == null ? Set.of() : values;
    }
}
