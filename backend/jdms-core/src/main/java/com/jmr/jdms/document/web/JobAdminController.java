package com.jmr.jdms.document.web;

import com.jmr.jdms.document.domain.JobAdministration;
import com.jmr.jdms.document.domain.JobAdministration.JobView;
import com.jmr.jdms.shared.job.JobStatus;
import com.jmr.jdms.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /admin/jobs}: the tenant's background jobs, e.g. {@code ?status=DEAD}, and retrying them. */
@RestController
@RequestMapping("/api/v1/admin/jobs")
@PreAuthorize("@access.can('ADMIN_METADATA')")
class JobAdminController {

    private final JobAdministration jobs;

    JobAdminController(JobAdministration jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    PageResponse<JobView> list(@RequestParam(required = false) JobStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(jobs.list(status, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC,
                "updatedAt"))), view -> view);
    }

    @PostMapping("/{id}/retry")
    JobView retry(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return jobs.retry(id);
    }
}
