package com.jmr.jdms.shared.job;

import com.jmr.jdms.contracts.JobType;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByType(JobType type);

    Page<Job> findByStatus(JobStatus status, Pageable pageable);
}
