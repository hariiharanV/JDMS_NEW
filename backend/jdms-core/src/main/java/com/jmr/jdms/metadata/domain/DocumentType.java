package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A kind of document within a cabinet (legacy {@code DOC_TYPE_MASTER}). Codes are unique per tenant, so integration
 * callers can name a type without its cabinet (ADR-0009).
 */
@Entity
@Table(name = "document_type")
public class DocumentType extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "cabinet_id", nullable = false)
    private UUID cabinetId;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(name = "name_en", nullable = false, length = 200)
    private String nameEn;

    @Column(name = "name_ar", length = 200)
    private String nameAr;

    @Column(nullable = false, length = 50)
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> channels;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_classification", nullable = false, length = 20)
    private Classification defaultClassification;

    @Column(name = "retention_policy_id")
    private UUID retentionPolicyId;

    @Column(name = "process_key", length = 100)
    private String processKey;

    @Column(name = "meta_change_needs_approval", nullable = false)
    private boolean metaChangeNeedsApproval;

    @Column(name = "delete_requires_approval", nullable = false)
    private boolean deleteRequiresApproval;

    @Column(name = "declare_record_on_approval", nullable = false)
    private boolean declareRecordOnApproval;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_standard_fields", nullable = false)
    private List<String> requiredStandardFields;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MetadataStatus status;

    protected DocumentType() {
    }

    public DocumentType(UUID id, UUID cabinetId, String code, DocumentTypeAttributes attributes) {
        this.id = id;
        this.cabinetId = cabinetId;
        this.code = code;
        update(attributes, MetadataStatus.ACTIVE);
    }

    public void update(DocumentTypeAttributes attributes, MetadataStatus newStatus) {
        this.nameEn = attributes.nameEn();
        this.nameAr = attributes.nameAr();
        this.category = attributes.category();
        this.channels = attributes.channels().stream().map(Enum::name).sorted().toList();
        this.defaultClassification = attributes.defaultClassification();
        this.processKey = attributes.processKey();
        this.metaChangeNeedsApproval = attributes.metaChangeNeedsApproval();
        this.deleteRequiresApproval = attributes.deleteRequiresApproval();
        this.declareRecordOnApproval = attributes.declareRecordOnApproval();
        this.requiredStandardFields = attributes.requiredStandardFields().stream().sorted().toList();
        this.status = newStatus;
    }

    public void deactivate() {
        this.status = MetadataStatus.INACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCabinetId() {
        return cabinetId;
    }

    public String getCode() {
        return code;
    }

    public String getNameEn() {
        return nameEn;
    }

    public String getNameAr() {
        return nameAr;
    }

    public String getCategory() {
        return category;
    }

    public List<String> getChannels() {
        return channels;
    }

    public Classification getDefaultClassification() {
        return defaultClassification;
    }

    void assignRetentionPolicy(UUID retentionPolicyId) {
        this.retentionPolicyId = retentionPolicyId;
    }

    public UUID getRetentionPolicyId() {
        return retentionPolicyId;
    }

    public String getProcessKey() {
        return processKey;
    }

    public boolean isMetaChangeNeedsApproval() {
        return metaChangeNeedsApproval;
    }

    public boolean isDeleteRequiresApproval() {
        return deleteRequiresApproval;
    }

    public boolean isDeclareRecordOnApproval() {
        return declareRecordOnApproval;
    }

    public List<String> getRequiredStandardFields() {
        return requiredStandardFields;
    }

    public MetadataStatus getStatus() {
        return status;
    }
}
