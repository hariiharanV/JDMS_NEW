package com.jmr.jdms.shared.persistence;

import com.jmr.jdms.shared.context.RequestContext;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Common columns for JDMS Next tables: who created and last changed the row, when (UTC), and an optimistic-lock
 * version ({@code version_lock}). Actors are the request's subject, or {@code system} outside a request.
 */
@MappedSuperclass
public abstract class AuditedEntity {

    public static final String SYSTEM_ACTOR = "system";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    @Version
    @Column(name = "version_lock", nullable = false)
    private long versionLock;

    @PrePersist
    void onCreate() {
        createdAt = now();
        createdBy = currentActor();
        updatedAt = createdAt;
        updatedBy = createdBy;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = now();
        updatedBy = currentActor();
    }

    private static Instant now() {
        // PostgreSQL stores microseconds; truncate so values read back equal values written
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String currentActor() {
        return RequestContext.current().map(context -> context.subjectId().toString()).orElse(SYSTEM_ACTOR);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public long getVersionLock() {
        return versionLock;
    }
}
