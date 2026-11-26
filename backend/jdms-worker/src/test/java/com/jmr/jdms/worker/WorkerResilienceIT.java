package com.jmr.jdms.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.ProcessingOutcome;
import com.jmr.jdms.contracts.ProcessingResult;
import com.jmr.jdms.contracts.ProcessingStep;
import com.jmr.jdms.contracts.RenditionBlob;
import com.jmr.jdms.platform.jobs.ClaimedJob;
import com.jmr.jdms.platform.jobs.JobRunner;
import com.jmr.jdms.platform.jobs.JobStore;
import com.jmr.jdms.worker.support.TestFiles;
import com.jmr.jdms.worker.support.WorkerContainers.SeaweedFs;
import com.jmr.jdms.worker.support.WorkerFixtures;
import com.jmr.jdms.worker.support.WorkerFixtures.Version;
import com.jmr.jdms.worker.support.WorkerIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

/**
 * B-13 resilience of the processing pipeline: a worker that dies holding a job, and an object store outage, end with
 * the version processed exactly once (one result per step, one rendition per kind) and nothing lost. The real
 * process kill against the local stack is rehearsed in {@code perf/resilience} (docs/perf/resilience.md).
 */
@WorkerIntegrationTest
class WorkerResilienceIT {

    @Autowired JobStore store;
    @Autowired TransactionTemplate transactions;
    @Autowired JobRunner runner;
    @Autowired WorkerFixtures fixtures;
    @Autowired SeaweedFs seaweedfs;

    @Test
    void aStepHeldByADeadWorkerIsFinishedByAnotherOnceItsLeaseExpires() throws Exception {
        Version version = fixtures.upload(TestFiles.textPdf("Collateral valuation for the crashed worker"),
                "application/pdf");
        List<ClaimedJob> held = transactions.execute(status -> store.claim(Set.of(JobType.SCAN.name()),
                "worker-killed-mid-job", 10, Duration.ofSeconds(2)));
        assertThat(held).hasSizeGreaterThanOrEqualTo(1); // the dead worker never completes or releases it

        runner.runUntilIdle();
        assertThat(fixtures.results(version)).isEmpty(); // still leased: nobody else may take it yet

        Thread.sleep(2_500);
        runner.runUntilIdle();

        List<ProcessingResult> results = fixtures.results(version);
        assertThat(results).extracting(ProcessingResult::step, ProcessingResult::outcome).containsExactly(
                tuple(ProcessingStep.SCAN, ProcessingOutcome.CLEAN),
                tuple(ProcessingStep.EXTRACT, ProcessingOutcome.DONE),
                tuple(ProcessingStep.RENDER, ProcessingOutcome.DONE));
        assertThat(results.stream().flatMap(result -> result.renditions().stream()).map(RenditionBlob::kind))
                .containsExactlyInAnyOrder("TEXT", "THUMBNAIL");
        assertThat(fixtures.jobs(version, "SCAN")).singleElement().satisfies(job ->
                assertThat(job).containsEntry("status", "DONE").containsEntry("attempts", 2));
    }

    @Test
    void processingWaitsOutAnObjectStoreOutage() throws Exception {
        Version version = fixtures.upload(TestFiles.textPdf("Statement processed after the outage"),
                "application/pdf");
        fixtures.allowAttempts(version, "SCAN", 5);
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(seaweedfs.getContainerId()).exec();
        CompletableFuture<Integer> running;
        try {
            running = CompletableFuture.supplyAsync(runner::runUntilIdle);
            // the first attempt cannot read the original and is scheduled for a retry
            await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(200)).until(() ->
                    fixtures.jobs(version, "SCAN").stream().anyMatch(job -> job.get("last_error") != null));
        } finally {
            docker.unpauseContainerCmd(seaweedfs.getContainerId()).exec();
        }
        running.get();
        runner.runUntilIdle();

        Map<String, Object> scan = fixtures.jobs(version, "SCAN").getFirst();
        assertThat(scan).containsEntry("status", "DONE");
        assertThat((int) scan.get("attempts")).isGreaterThan(1);
        assertThat(fixtures.results(version)).extracting(ProcessingResult::step, ProcessingResult::outcome)
                .containsExactly(tuple(ProcessingStep.SCAN, ProcessingOutcome.CLEAN),
                        tuple(ProcessingStep.EXTRACT, ProcessingOutcome.DONE),
                        tuple(ProcessingStep.RENDER, ProcessingOutcome.DONE));
    }
}
