package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** One value of a LOV. Codes never change; values in use are deactivated rather than removed. */
@Entity
@Table(name = "lov_value")
public class LovValue extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "lov_id", nullable = false)
    private UUID lovId;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(name = "label_en", nullable = false, length = 200)
    private String labelEn;

    @Column(name = "label_ar", length = 200)
    private String labelAr;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    protected LovValue() {
    }

    public LovValue(UUID lovId, String code, String labelEn, String labelAr, int displayOrder, boolean active) {
        this.id = Ids.newId();
        this.lovId = lovId;
        this.code = code;
        update(labelEn, labelAr, displayOrder, active);
    }

    public void update(String newLabelEn, String newLabelAr, int newDisplayOrder, boolean newActive) {
        this.labelEn = newLabelEn;
        this.labelAr = newLabelAr;
        this.displayOrder = newDisplayOrder;
        this.active = newActive;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLovId() {
        return lovId;
    }

    public String getCode() {
        return code;
    }

    public String getLabelEn() {
        return labelEn;
    }

    public String getLabelAr() {
        return labelAr;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActive() {
        return active;
    }
}
