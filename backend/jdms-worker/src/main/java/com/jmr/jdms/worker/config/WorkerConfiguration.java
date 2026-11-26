package com.jmr.jdms.worker.config;

import com.jmr.jdms.platform.content.BlobStore;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.KeyService;
import com.jmr.jdms.platform.content.LocalKeyService;
import com.jmr.jdms.platform.content.S3BlobStore;
import com.jmr.jdms.platform.jobs.JobHandler;
import com.jmr.jdms.platform.jobs.JobRunner;
import com.jmr.jdms.platform.jobs.JobStore;
import com.jmr.jdms.platform.jobs.infra.PostgresJobStore;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** Storage, encryption and the job runner, from the shared platform module (ADR-0014). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WorkerProperties.class, StorageProperties.class, KmsProperties.class})
class WorkerConfiguration {

    @Bean
    S3BlobStore blobStore(StorageProperties properties) {
        return new S3BlobStore(new S3BlobStore.Settings(properties.endpoint(), properties.region(),
                properties.bucket(), properties.accessKey(), properties.secretKey(), properties.pathStyleAccess(),
                properties.createBucket(), properties.timeout()));
    }

    @Bean
    KeyService keyService(KmsProperties properties) {
        return new LocalKeyService(properties.localMasterKey(), properties.generateEphemeralKey());
    }

    @Bean
    EnvelopeEncryption envelopeEncryption(BlobStore blobs, KeyService keys) {
        return new EnvelopeEncryption(blobs, keys);
    }

    @Bean
    JobStore jobStore(DataSource dataSource) {
        return new PostgresJobStore(dataSource);
    }

    @Bean(destroyMethod = "close")
    JobRunner workerJobRunner(JobStore store, TransactionTemplate transactions, List<JobHandler> handlers,
            WorkerProperties properties) {
        return new JobRunner(store, transactions, handlers, new JobRunner.Settings("jdms-worker-"
                + ProcessHandle.current().pid(), properties.pollInterval(), properties.concurrency(),
                properties.lease(), properties.backoffBase(), properties.backoffMax(), properties.maxAttempts()));
    }

    @Bean
    Starter workerJobRunnerStarter(JobRunner workerJobRunner, WorkerProperties properties) {
        return new Starter(workerJobRunner, properties.autoStart());
    }

    record Starter(JobRunner runner, boolean enabled) {

        @EventListener(ApplicationReadyEvent.class)
        void start() {
            if (enabled) {
                runner.start();
            }
        }
    }
}
