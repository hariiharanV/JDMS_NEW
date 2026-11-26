package com.jmr.jdms.records.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
/** How long documents of the assigned types are kept, and what happens then (spec §4.8). */
@Entity
@Table(name = "retention_policy")
public class RetentionPolicy extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private RetentionTrigger triggerType;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(name = "period_months", nullable = false)
    private int periodMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition_action", nullable = false, length = 20)
    private DispositionAction dispositionAction;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    protected RetentionPolicy() {
    }

    public RetentionPolicy(UUID id, String code, String name, RetentionTrigger triggerType, String eventType, int periodMonths,
            DispositionAction dispositionAction, boolean requiresApproval) {
        this.id = id;
        this.code = code;
        update(name, triggerType, eventType, periodMonths, dispositionAction, requiresApproval);
    }

    void update(String name, RetentionTrigger triggerType, String eventType, int periodMonths,
            DispositionAction dispositionAction, boolean requiresApproval) {
        this.name = name;
        this.triggerType = triggerType;
        this.eventType = eventType;
        this.periodMonths = periodMonths;
        this.dispositionAction = dispositionAction;
        this.requiresApproval = requiresApproval;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public RetentionTrigger getTriggerType() {
        return triggerType;
    }

    public String getEventType() {
        return eventType;
    }

    public int getPeriodMonths() {
        return periodMonths;
    }

    public DispositionAction getDispositionAction() {
        return dispositionAction;
    }

    public boolean isRequiresApproval() {
        return requiresApproval;
    }
}
