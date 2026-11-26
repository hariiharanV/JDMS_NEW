package com.jmr.jdms.document.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.job.Job;
import com.jmr.jdms.shared.job.JobRepository;
import com.jmr.jdms.shared.job.JobStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Background jobs of the tenant for operations: list (e.g. {@code DEAD}) and retry (spec B-06). */
@Service
public class JobAdministration {

    public static final String JOB_NOT_FOUND = "JOB_NOT_FOUND";
    public static final String JOB_NOT_RETRYABLE = "JOB_NOT_RETRYABLE";

    /** Payloads are IDs only (no content); errors are class and message of the failing call. */
    public record JobView(UUID id, String type, JobStatus status, int attempts, int maxAttempts, Instant runAfter,
            String lastError, String payload, Instant createdAt, Instant updatedAt) {

        static JobView of(Job job) {
            return new JobView(job.getId(), job.getType().name(), job.getStatus(), job.getAttempts(),
                    job.getMaxAttempts(), job.getRunAfter(), job.getLastError(), job.getPayload(), job.getCreatedAt(),
                    job.getUpdatedAt());
        }
    }

    private final JobRepository jobs;
    private final AuditTrail auditTrail;

    JobAdministration(JobRepository jobs, AuditTrail auditTrail) {
        this.jobs = jobs;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public Page<JobView> list(JobStatus status, Pageable pageable) {
        return (status == null ? jobs.findAll(pageable) : jobs.findByStatus(status, pageable)).map(JobView::of);
    }

    @Transactional
    public JobView retry(UUID id) {
        Job job = jobs.findById(id).orElseThrow(() -> ApiException.notFound(JOB_NOT_FOUND, "Job not found."));
        if (job.getStatus() != JobStatus.DEAD && job.getStatus() != JobStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, JOB_NOT_RETRYABLE, "Only failed or dead jobs can be retried.");
        }
        JobStatus before = job.getStatus();
        job.requeue(Instant.now().truncatedTo(ChronoUnit.MICROS));
        jobs.flush();
        auditTrail.record(AuditEntry.success("JOB_RETRIED", "JOB", id, Map.of("type", job.getType().name(),
                "previousStatus", before.name())));
        return JobView.of(job);
    }
}
