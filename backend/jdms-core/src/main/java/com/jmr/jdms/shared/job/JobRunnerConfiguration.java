package com.jmr.jdms.shared.job;

import com.jmr.jdms.platform.jobs.JobHandler;
import com.jmr.jdms.platform.jobs.JobRunner;
import com.jmr.jdms.platform.jobs.JobStore;
import com.jmr.jdms.platform.jobs.infra.PostgresJobStore;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The core's job runner (ADR-0014): runs every {@link JobHandler} bean of the core, e.g. applying processing results
 * from the worker and search indexing. The worker runs its own runner for its job types.
 */
@Configuration(proxyBeanMethods = false)
class JobRunnerConfiguration {

    @Bean
    JobStore jobStore(DataSource dataSource) {
        return new PostgresJobStore(dataSource);
    }

    @Bean(destroyMethod = "close")
    JobRunner coreJobRunner(JobStore store, TransactionTemplate transactions, List<JobHandler> handlers,
            @Value("${jdms.jobs.poll-interval:PT1S}") Duration pollInterval) {
        return new JobRunner(store, transactions, handlers, new JobRunner.Settings("jdms-core", pollInterval, 4,
                Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofMinutes(5), 10));
    }

    @Bean
    Starter coreJobRunnerStarter(JobRunner coreJobRunner) {
        return new Starter(coreJobRunner);
    }

    /** Starts polling once the application is ready. */
    record Starter(JobRunner runner) {

        @EventListener(ApplicationReadyEvent.class)
        void start() {
            runner.start();
        }
    }
}
