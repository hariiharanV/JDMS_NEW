package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A message for one user (e.g. their upload was quarantined). In-app only; email and SMS are out of scope. */
@Entity
@Table(name = "notification")
public class Notification extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, length = 50)
    private String type;

    @Column(name = "document_id", updatable = false)
    private UUID documentId;

    @Column(name = "version_id", updatable = false)
    private UUID versionId;

    @Column(nullable = false, updatable = false, length = 500)
    private String message;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    public Notification(UUID userId, String type, UUID documentId, UUID versionId, String message) {
        this.id = Ids.newId();
        this.userId = userId;
        this.type = type;
        this.documentId = documentId;
        this.versionId = versionId;
        this.message = message;
    }

    void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getType() {
        return type;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public String getMessage() {
        return message;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
