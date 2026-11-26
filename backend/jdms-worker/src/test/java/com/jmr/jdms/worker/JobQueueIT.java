package com.jmr.jdms.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.contracts.ProcessingOutcome;
import com.jmr.jdms.contracts.ProcessingResult;
import com.jmr.jdms.contracts.ProcessingStep;
import com.jmr.jdms.platform.jobs.ClaimedJob;
import com.jmr.jdms.platform.jobs.JobHandler;
import com.jmr.jdms.platform.jobs.JobOutcome;
import com.jmr.jdms.platform.jobs.JobRunner;
import com.jmr.jdms.platform.jobs.JobStore;
import com.jmr.jdms.platform.jobs.NewJob;
import com.jmr.jdms.worker.support.TestFiles;
import com.jmr.jdms.worker.support.WorkerFixtures;
import com.jmr.jdms.worker.support.WorkerFixtures.Version;
import com.jmr.jdms.worker.support.WorkerIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * B-06 job queue on PostgreSQL: concurrent runners never claim the same job ({@code SKIP LOCKED}); an expired lease
 * makes a job claimable again; a hanging service is retried until the job is {@code DEAD}, and the failure is
 * reported to the core.
 */
@WorkerIntegrationTest
class JobQueueIT {

    @Autowired JobStore store;
    @Autowired TransactionTemplate transactions;
    @Autowired JobRunner runner;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired WorkerFixtures fixtures;
    @Autowired @Qualifier("tika") GenericContainer<?> tika;

    @Test
    void concurrentRunnersClaimDisjointJobs() throws Exception {
        String type = "TEST_" + UUID.randomUUID().toString().substring(0, 8);
        Version owner = fixtures.upload(TestFiles.random(10), "application/octet-stream"); // provides a tenant
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < 40; i++) {
                store.enqueue(new NewJob(owner.tenantId(), type, "{}"), 3);
            }
        });

        List<Callable<List<ClaimedJob>>> claims = new ArrayList<>();
        for (int runnerNo = 0; runnerNo < 4; runnerNo++) {
            String name = "runner-" + runnerNo;
            claims.add(() -> transactions.execute(status -> store.claim(Set.of(type), name, 10,
                    Duration.ofMinutes(5))));
        }
        ExecutorService executor = Executors.newFixedThreadPool(4);
        Set<UUID> claimed = new HashSet<>();
        int total = 0;
        for (Future<List<ClaimedJob>> result : executor.invokeAll(claims)) {
            for (ClaimedJob job : result.get()) {
                claimed.add(job.id());
                total++;
            }
        }
        executor.shutdown();

        assertThat(total).isEqualTo(40);
        assertThat(claimed).hasSize(40);
    }

    @Test
    void anExpiredLeaseMakesTheJobClaimableAgain() {
        String type = "TEST_" + UUID.randomUUID().toString().substring(0, 8);
        Version owner = fixtures.upload(TestFiles.random(10), "application/octet-stream");
        UUID id = transactions.execute(status -> store.enqueue(new NewJob(owner.tenantId(), type, "{}"), 3));

        ClaimedJob first = transactions.execute(status -> store.claim(Set.of(type), "crashed", 1, Duration.ZERO))
                .getFirst();
        ClaimedJob second = transactions.execute(status -> store.claim(Set.of(type), "next", 1,
                Duration.ofMinutes(5))).getFirst();

        assertThat(first.id()).isEqualTo(id);
        assertThat(second.id()).isEqualTo(id);
        assertThat(second.attempts()).isEqualTo(2);
        List<ClaimedJob> third = transactions.execute(status -> store.claim(Set.of(type), "other", 1,
                Duration.ofMinutes(5)));
        assertThat(third).isEmpty();
    }

    @Test
    void aLongJobKeepsItsLeaseWhileItsRunnerIsAlive() throws Exception {
        String type = "TEST_" + UUID.randomUUID().toString().substring(0, 8);
        Version owner = fixtures.upload(TestFiles.random(10), "application/octet-stream");
        UUID id = transactions.execute(status -> store.enqueue(new NewJob(owner.tenantId(), type, "{}"), 3));
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        JobHandler slow = new JobHandler() {
            @Override
            public Set<String> types() {
                return Set.of(type);
            }

            @Override
            public JobOutcome handle(ClaimedJob job) throws Exception {
                started.countDown();
                Thread.sleep(3_000); // three leases long
                return JobOutcome.done();
            }
        };
        try (JobRunner live = new JobRunner(store, transactions, List.of(slow), new JobRunner.Settings("live",
                Duration.ofMillis(50), 1, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, 3))) {
            live.start();
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Thread.sleep(2_000); // past the first lease: renewed by the heartbeat, so nobody can take it
            List<ClaimedJob> takenOver = transactions.execute(status -> store.claim(Set.of(type), "other", 1,
                    Duration.ofMinutes(5)));
            assertThat(takenOver).isEmpty();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> "DONE".equals(
                    jdbc.queryForObject("select status from job where id = ?", String.class, id)));
        }
        assertThat(jdbc.queryForObject("select attempts from job where id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void aHangingServiceIsRetriedUntilTheJobIsDead() throws Exception {
        Version version = fixtures.upload(TestFiles.textPdf("Waiting for Tika"), "application/pdf");
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(tika.getContainerId()).exec();
        try {
            runner.runUntilIdle(); // SCAN passes; EXTRACT times out twice (max attempts 2, no backoff in tests)
        } finally {
            docker.unpauseContainerCmd(tika.getContainerId()).exec();
        }

        assertThat(fixtures.jobs(version, "EXTRACT")).singleElement().satisfies(job -> {
            assertThat(job).containsEntry("status", "DEAD").containsEntry("attempts", 2);
            assertThat((String) job.get("last_error")).contains("Timeout");
        });
        ProcessingResult last = fixtures.results(version).getLast();
        assertThat(last.step()).isEqualTo(ProcessingStep.EXTRACT);
        assertThat(last.outcome()).isEqualTo(ProcessingOutcome.FAILED);
        assertThat(last.last()).isTrue();
    }
}
