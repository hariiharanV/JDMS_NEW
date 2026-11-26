package com.jmr.jdms.shared.job;

import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A unit of background work (DM {@code job}). Consumers poll with {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * (worker, B-06); the core only enqueues here.
 */
@Entity
@Table(name = "job")
public class Job extends TenantScopedEntity {

    /**
     * With the runners' backoff (core: 5 s doubling to 5 min; worker: 30 s doubling to 30 min) ten attempts ride out an
     * outage of about 20 minutes (core jobs) or 2 hours (worker jobs) before a job is dead (B-13 resilience).
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 10;

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "run_after", nullable = false)
    private Instant runAfter;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    protected Job() {
    }

    Job(JobType type, String payload, Instant runAfter) {
        this.id = Ids.newId();
        this.type = type;
        this.payload = payload;
        this.status = JobStatus.READY;
        this.maxAttempts = DEFAULT_MAX_ATTEMPTS;
        this.runAfter = runAfter;
    }

    /** Requeues a failed or dead job: a fresh set of attempts, due now. */
    public void requeue(Instant now) {
        if (status != JobStatus.FAILED && status != JobStatus.DEAD) {
            throw new IllegalStateException("Only failed or dead jobs can be retried");
        }
        this.status = JobStatus.READY;
        this.attempts = 0;
        this.runAfter = now;
    }

    public UUID getId() {
        return id;
    }

    public JobType getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getRunAfter() {
        return runAfter;
    }

    public String getLastError() {
        return lastError;
    }
}
