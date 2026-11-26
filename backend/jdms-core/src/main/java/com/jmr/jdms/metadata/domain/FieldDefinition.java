package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** One custom field of a document type (legacy {@code DOC_FIELD_DETAILS}). */
@Entity
@Table(name = "field_definition")
public class FieldDefinition extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(name = "field_key", nullable = false, length = 50)
    private String fieldKey;

    @Column(name = "label_en", nullable = false, length = 200)
    private String labelEn;

    @Column(name = "label_ar", length = 200)
    private String labelAr;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 10)
    private FieldDataType dataType;

    @Column(nullable = false)
    private boolean mandatory;

    @Column(name = "max_length")
    private Integer maxLength;

    @Column(name = "default_value", length = 4000)
    private String defaultValue;

    @Column(name = "lov_code", length = 30)
    private String lovCode;

    @Column(nullable = false)
    private boolean searchable;

    @Column(nullable = false)
    private boolean facet;

    @Column(nullable = false)
    private boolean sensitive;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "display_row", nullable = false)
    private int displayRow;

    protected FieldDefinition() {
    }

    public FieldDefinition(UUID id, UUID documentTypeId, FieldSpec spec) {
        this.id = id;
        this.documentTypeId = documentTypeId;
        this.fieldKey = spec.key();
        update(spec);
    }

    public void update(FieldSpec spec) {
        this.labelEn = spec.labelEn();
        this.labelAr = spec.labelAr();
        this.dataType = spec.dataType();
        this.mandatory = spec.mandatory();
        this.maxLength = spec.maxLength();
        this.defaultValue = spec.defaultValue();
        this.lovCode = spec.lovCode();
        this.searchable = spec.searchable();
        this.facet = spec.facet();
        this.sensitive = spec.sensitive();
        this.displayOrder = spec.displayOrder();
        this.displayRow = spec.displayRow();
    }

    public FieldSpec spec() {
        return new FieldSpec(fieldKey, labelEn, labelAr, dataType, mandatory, maxLength, defaultValue, lovCode,
                searchable, facet, sensitive, displayOrder, displayRow);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }

    public String getFieldKey() {
        return fieldKey;
    }
}
