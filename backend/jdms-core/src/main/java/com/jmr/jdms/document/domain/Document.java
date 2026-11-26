package com.jmr.jdms.document.domain;

import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A document: metadata, status and lock; content lives in immutable {@link DocumentVersion}s (legacy
 * {@code DMS_DOCUMENT_UPLOAD} + {@code DMS_CUSTOM_DOCFIELD_DTS}). Custom fields are canonical JSON, validated and
 * normalised by the metadata module before they are stored.
 */
@Entity
@Table(name = "document")
public class Document extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "cabinet_id", nullable = false)
    private UUID cabinetId;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Classification classification;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(length = 50)
    private String cif;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "account_no", length = 50)
    private String accountNo;

    @Column(name = "collateral_code", length = 50)
    private String collateralCode;

    @Column(name = "contract_ref", length = 50)
    private String contractRef;

    @Column(name = "transaction_ref", length = 50)
    private String transactionRef;

    @Column(name = "application_ref", length = 50)
    private String applicationRef;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "physical_location", length = 200)
    private String physicalLocation;

    @Column(name = "physical_ref", length = 100)
    private String physicalRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", nullable = false)
    private String customFields;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "checked_out_by")
    private UUID checkedOutBy;

    @Column(name = "checked_out_at")
    private Instant checkedOutAt;

    @Column(name = "record_declared_at")
    private Instant recordDeclaredAt;

    @Column(name = "retention_policy_id")
    private UUID retentionPolicyId;

    @Column(name = "retention_until")
    private LocalDate retentionUntil;

    @Column(name = "on_legal_hold", nullable = false)
    private boolean onLegalHold;

    @Column(name = "source_system", nullable = false, length = 100)
    private String sourceSystem;

    @Column(name = "external_ref", length = 100)
    private String externalRef;

    @Column(name = "legacy_document_id", length = 100, updatable = false)
    private String legacyDocumentId;

    @Column(name = "legacy_created_at", updatable = false)
    private Instant legacyCreatedAt;

    @Column(name = "legacy_created_by", length = 100, updatable = false)
    private String legacyCreatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before_delete", length = 20)
    private DocumentStatus statusBeforeDelete;

    protected Document() {
    }

    public Document(UUID cabinetId, UUID documentTypeId, DocumentStatus status, String sourceSystem,
            DocumentMetadata metadata) {
        this.id = Ids.newId();
        this.cabinetId = cabinetId;
        this.documentTypeId = documentTypeId;
        this.status = status;
        this.sourceSystem = sourceSystem;
        apply(metadata);
    }

    /** Replaces all editable metadata. */
    public void apply(DocumentMetadata metadata) {
        this.title = metadata.title();
        this.folderId = metadata.folderId();
        this.classification = metadata.classification();
        this.branchId = metadata.branchId();
        StandardMetadata standard = metadata.standard();
        this.cif = standard.cif();
        this.customerName = standard.customerName();
        this.accountNo = standard.accountNo();
        this.collateralCode = standard.collateralCode();
        this.contractRef = standard.contractRef();
        this.transactionRef = standard.transactionRef();
        this.applicationRef = standard.applicationRef();
        this.expiryDate = standard.expiryDate();
        this.physicalLocation = standard.physicalLocation();
        this.physicalRef = standard.physicalRef();
        this.customFields = metadata.customFieldsJson();
        this.externalRef = metadata.externalRef();
    }

    public DocumentMetadata metadata() {
        return new DocumentMetadata(title, folderId, classification, branchId, new StandardMetadata(cif, customerName,
                accountNo, collateralCode, contractRef, transactionRef, applicationRef, expiryDate, physicalLocation,
                physicalRef), customFields, externalRef);
    }

    /** Approval outcomes (spec §4.4, §5): a returned upload is a draft until the maker resubmits it. */
    public void approvalOutcome(DocumentStatus outcome) {
        if (status != DocumentStatus.PENDING_APPROVAL && status != DocumentStatus.DRAFT) {
            throw new IllegalStateException("Document " + id + " is not in approval (" + status + ")");
        }
        this.status = outcome;
    }

    /** Marks a document imported from legacy JDMS (ADR-0020); set once, before the first save. */
    void importedFrom(String legacyId, Instant createdAt, String createdBy) {
        this.legacyDocumentId = legacyId;
        this.legacyCreatedAt = createdAt;
        this.legacyCreatedBy = createdBy;
    }

    public String getLegacyDocumentId() {
        return legacyDocumentId;
    }

    /** Soft delete (spec §4.7): to the recycle bin, remembering the status to restore. */
    void moveToRecycleBin(UUID by, Instant at) {
        this.statusBeforeDelete = status;
        this.status = DocumentStatus.DELETED;
        this.deletedAt = at;
        this.deletedBy = by;
    }

    void restoreFromRecycleBin() {
        this.status = statusBeforeDelete == null ? DocumentStatus.ACTIVE : statusBeforeDelete;
        this.statusBeforeDelete = null;
        this.deletedAt = null;
        this.deletedBy = null;
    }

    void placeOnHold(boolean held) {
        this.onLegalHold = held;
    }

    void declareRecord(Instant at) {
        if (recordDeclaredAt == null) {
            this.recordDeclaredAt = at;
        }
    }

    /** Disposition (spec §4.8): the metadata is reduced; a tombstone in the records module keeps the evidence. */
    void dispose() {
        this.status = DocumentStatus.DISPOSED;
        this.title = "Disposed document";
        this.cif = null;
        this.customerName = null;
        this.accountNo = null;
        this.collateralCode = null;
        this.contractRef = null;
        this.transactionRef = null;
        this.applicationRef = null;
        this.physicalLocation = null;
        this.physicalRef = null;
        this.externalRef = null;
        this.customFields = "{}";
        this.checkedOutBy = null;
        this.checkedOutAt = null;
    }

    public void makeCurrent(UUID versionId) {
        this.currentVersionId = versionId;
    }

    public void checkOut(UUID userId, Instant at) {
        this.checkedOutBy = Objects.requireNonNull(userId);
        this.checkedOutAt = at;
    }

    public void releaseCheckOut() {
        this.checkedOutBy = null;
        this.checkedOutAt = null;
    }

    public boolean isCheckedOutBy(UUID userId) {
        return userId.equals(checkedOutBy);
    }

    public boolean isCheckedOut() {
        return checkedOutBy != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCabinetId() {
        return cabinetId;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public String getTitle() {
        return title;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public Classification getClassification() {
        return classification;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public String getCif() {
        return cif;
    }

    public String getCustomFields() {
        return customFields;
    }

    public UUID getCurrentVersionId() {
        return currentVersionId;
    }

    public UUID getCheckedOutBy() {
        return checkedOutBy;
    }

    public Instant getCheckedOutAt() {
        return checkedOutAt;
    }

    public boolean isOnLegalHold() {
        return onLegalHold;
    }

    public Instant getRecordDeclaredAt() {
        return recordDeclaredAt;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getDeletedBy() {
        return deletedBy;
    }

    public DocumentStatus getStatusBeforeDelete() {
        return statusBeforeDelete;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public String getAccountNo() {
        return accountNo;
    }
}
