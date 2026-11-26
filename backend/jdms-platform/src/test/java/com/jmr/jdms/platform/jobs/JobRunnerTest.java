package com.jmr.jdms.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JobRunnerTest {

    static final UUID TENANT = UUID.randomUUID();

    final FakeStore store = new FakeStore();
    final JobRunner.Settings settings = new JobRunner.Settings("test", Duration.ofMillis(10), 2,
            Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMinutes(10), 3);

    @Test
    void successCompletesTheJobAndEnqueuesFollowUps() {
        UUID id = store.enqueue(new NewJob(TENANT, "A", "{}"), 3);
        runner(job -> JobOutcome.then(new NewJob(TENANT, "B", "{\"x\":1}"))).runUntilIdle();

        assertThat(store.status(id)).isEqualTo("DONE");
        assertThat(store.jobs.values()).extracting(FakeJob::type).containsExactly("A", "B");
    }

    @Test
    void transientFailuresAreRetriedWithBackoffThenDead() {
        UUID id = store.enqueue(new NewJob(TENANT, "A", "{}"), 3);
        JobRunner runner = runner(job -> {
            throw new IllegalStateException("tika unavailable");
        });

        runner.runUntilIdle();
        assertThat(store.status(id)).isEqualTo("READY");
        assertThat(store.jobs.get(id).runAfter).isAfter(Instant.now().plusSeconds(25));
        assertThat(store.jobs.get(id).error).isEqualTo("IllegalStateException: tika unavailable");

        store.makeDue(id);
        runner.runUntilIdle();
        store.makeDue(id);
        runner.runUntilIdle();

        assertThat(store.status(id)).isEqualTo("DEAD");
        assertThat(store.jobs.get(id).attempts).isEqualTo(3);
        assertThat(store.jobs.values()).extracting(FakeJob::type).contains("REPORT");
    }

    @Test
    void permanentFailuresAreNotRetried() {
        UUID id = store.enqueue(new NewJob(TENANT, "A", "{}"), 3);
        runner(job -> {
            throw new PermanentJobFailure("Malformed PDF");
        }).runUntilIdle();

        assertThat(store.status(id)).isEqualTo("FAILED");
        assertThat(store.jobs.get(id).attempts).isEqualTo(1);
        assertThat(store.jobs.get(id).error).isEqualTo("Malformed PDF");
        assertThat(store.jobs.values()).extracting(FakeJob::type).contains("REPORT");
    }

    @Test
    void backoffDoublesUpToTheCap() {
        JobRunner runner = runner(job -> JobOutcome.done());
        assertThat(runner.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(runner.backoff(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(runner.backoff(10)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void runningJobsHaveTheirLeaseRenewedUntilTheyFinish() throws Exception {
        UUID id = store.enqueue(new NewJob(TENANT, "A", "{}"), 3);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        JobRunner runner = new JobRunner(store, new TransactionTemplate(new NoTransactions()), List.of(handler(job -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return JobOutcome.done();
        })), new JobRunner.Settings("test", Duration.ofMillis(10), 1, Duration.ofMillis(150), Duration.ofSeconds(1),
                Duration.ofSeconds(1), 3));
        try {
            runner.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> store.extended.size() >= 3);
            assertThat(store.extended).containsOnly(id);
            release.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> "DONE".equals(store.status(id)));
            int renewals = store.extended.size();
            Thread.sleep(200);
            assertThat(store.extended).hasSize(renewals); // finished jobs are no longer renewed
        } finally {
            release.countDown();
            runner.close();
        }
    }

    JobRunner runner(Function<ClaimedJob, JobOutcome> behaviour) {
        return new JobRunner(store, new TransactionTemplate(new NoTransactions()), List.of(handler(behaviour)),
                settings);
    }

    static JobHandler handler(Function<ClaimedJob, JobOutcome> behaviour) {
        JobHandler handler = new JobHandler() {
            @Override
            public Set<String> types() {
                return Set.of("A");
            }

            @Override
            public JobOutcome handle(ClaimedJob job) {
                return behaviour.apply(job);
            }

            @Override
            public List<NewJob> onGiveUp(ClaimedJob job, String reason) {
                return List.of(new NewJob(job.tenantId(), "REPORT", "{}"));
            }
        };
        return handler;
    }

    static final class FakeJob {
        final UUID id;
        final String type;
        String status = "READY";
        int attempts;
        final int max;
        Instant runAfter = Instant.now();
        String error;

        FakeJob(UUID id, String type, int max) {
            this.id = id;
            this.type = type;
            this.max = max;
        }

        String type() {
            return type;
        }
    }

    static final class FakeStore implements JobStore {
        final Map<UUID, FakeJob> jobs = new LinkedHashMap<>();

        String status(UUID id) {
            return jobs.get(id).status;
        }

        void makeDue(UUID id) {
            jobs.get(id).runAfter = Instant.now();
        }

        @Override
        public synchronized List<ClaimedJob> claim(Set<String> types, String runner, int limit, Duration lease) {
            List<ClaimedJob> claimed = new ArrayList<>();
            for (FakeJob job : jobs.values()) {
                if (claimed.size() < limit && types.contains(job.type) && job.status.equals("READY")
                        && !job.runAfter.isAfter(Instant.now())) {
                    job.status = "RUNNING";
                    job.attempts++;
                    claimed.add(new ClaimedJob(job.id, TENANT, job.type, "{}", job.attempts, job.max));
                }
            }
            return claimed;
        }

        final List<UUID> extended = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public synchronized int extend(java.util.Collection<UUID> ids, String runner, Duration lease) {
            extended.addAll(ids);
            return ids.size();
        }

        @Override
        public synchronized void complete(UUID id) {
            jobs.get(id).status = "DONE";
        }

        @Override
        public void retryAt(UUID id, Instant runAfter, String error) {
            FakeJob job = jobs.get(id);
            job.status = "READY";
            job.runAfter = runAfter;
            job.error = error;
        }

        @Override
        public void fail(UUID id, String error) {
            jobs.get(id).status = "FAILED";
            jobs.get(id).error = error;
        }

        @Override
        public void dead(UUID id, String error) {
            jobs.get(id).status = "DEAD";
            jobs.get(id).error = error;
        }

        @Override
        public synchronized UUID enqueue(NewJob job, int maxAttempts) {
            UUID id = UUID.randomUUID();
            jobs.put(id, new FakeJob(id, job.type(), maxAttempts));
            return id;
        }
    }

    static final class NoTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
