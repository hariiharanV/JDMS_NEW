package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.GrantSource;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A time-bound grant of actions on one document to one user (spec §6 step 7). */
@Entity
@Table(name = "document_grant")
public class DocumentGrant extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> actions;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private GrantSource source;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected DocumentGrant() {
    }

    public DocumentGrant(UUID documentId, UUID userId, Set<Action> actions, Instant validUntil, GrantSource source,
            UUID sourceId) {
        this.id = Ids.newId();
        this.documentId = documentId;
        this.userId = userId;
        this.actions = actions.stream().map(Enum::name).sorted().toList();
        this.validUntil = validUntil;
        this.source = source;
        this.sourceId = sourceId;
    }

    public void revoke(Instant at) {
        this.revokedAt = at;
    }

    ActiveGrant active() {
        return new ActiveGrant(id, documentId, actions.stream().map(Action::valueOf).collect(Collectors.toSet()),
                validUntil, revokedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getUserId() {
        return userId;
    }
}
