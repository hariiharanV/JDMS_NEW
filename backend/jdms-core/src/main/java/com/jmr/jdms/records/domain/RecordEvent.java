package com.jmr.jdms.records.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A business event that starts event-based retention, e.g. {@code ACCOUNT_CLOSED} for an account number. */
@Entity
@Table(name = "record_event")
public class RecordEvent extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    private String eventType;

    @Column(name = "account_no", nullable = false, length = 50, updatable = false)
    private String accountNo;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected RecordEvent() {
    }

    public RecordEvent(String eventType, String accountNo, Instant occurredAt) {
        this.id = Ids.newId();
        this.eventType = eventType;
        this.accountNo = accountNo;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
