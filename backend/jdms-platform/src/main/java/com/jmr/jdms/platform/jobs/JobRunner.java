package com.jmr.jdms.platform.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the job queue for its handlers' types and runs each job on a virtual thread. Success completes the job and
 * enqueues its follow-ups in one transaction; {@link PermanentJobFailure} fails it; anything else is retried with
 * exponential backoff ({@code backoffBase × 2^(attempt-1)}, capped) until {@code max_attempts}, then the job is
 * {@code DEAD}. Handlers run outside any transaction (they do slow I/O). While a job runs, its lease is renewed every
 * third of the lease (heartbeat): a long job keeps its claim, and the jobs of a runner that died are taken over by
 * another one within one lease (B-13).
 */
public class JobRunner implements AutoCloseable {

    /**
     * @param name        identifies this runner in {@code locked_by}
     * @param concurrency jobs running at once
     * @param lease       how long a claim is held before another runner may take the job over
     */
    public record Settings(String name, Duration pollInterval, int concurrency, Duration lease, Duration backoffBase,
            Duration backoffMax, int maxAttempts) {
    }

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobStore store;
    private final TransactionTemplate transactions;
    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final Settings settings;
    private final Semaphore slots;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService poller;

    public JobRunner(JobStore store, TransactionTemplate transactions, List<JobHandler> handlers, Settings settings) {
        this.store = store;
        this.transactions = transactions;
        this.settings = settings;
        this.slots = new Semaphore(settings.concurrency());
        handlers.forEach(handler -> handler.types().forEach(type -> this.handlers.put(type, handler)));
    }

    /** Starts polling in the background. */
    public synchronized void start() {
        if (poller == null) {
            poller = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(settings.name() + "-poller")
                    .daemon().factory());
            poller.scheduleWithFixedDelay(this::pollSafely, 0, settings.pollInterval().toMillis(),
                    TimeUnit.MILLISECONDS);
            long heartbeat = Math.max(1, settings.lease().toMillis() / 3);
            poller.scheduleWithFixedDelay(this::heartbeat, heartbeat, heartbeat, TimeUnit.MILLISECONDS);
        }
    }

    /** Claims and runs due jobs on the calling thread until none is left; for tests and tools. */
    public int runUntilIdle() {
        int total = 0;
        List<ClaimedJob> batch;
        do {
            batch = store.claim(handlers.keySet(), settings.name(), settings.concurrency(), settings.lease());
            batch.forEach(this::run);
            total += batch.size();
        } while (!batch.isEmpty());
        return total;
    }

    private void pollSafely() {
        try {
            int free = slots.availablePermits();
            if (free == 0) {
                return;
            }
            for (ClaimedJob job : store.claim(handlers.keySet(), settings.name(), free, settings.lease())) {
                slots.acquireUninterruptibly();
                workers.submit(() -> {
                    try {
                        run(job);
                    } finally {
                        slots.release();
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("Job polling failed: {}", e.toString());
        }
    }

    /** Renews the leases of the jobs running here. */
    void heartbeat() {
        try {
            if (!running.isEmpty()) {
                store.extend(Set.copyOf(running), settings.name(), settings.lease());
            }
        } catch (RuntimeException e) {
            log.warn("Renewing job leases failed: {}", e.toString());
        }
    }

    void run(ClaimedJob job) {
        running.add(job.id());
        try {
            runClaimed(job);
        } finally {
            running.remove(job.id());
        }
    }

    private void runClaimed(ClaimedJob job) {
        JobHandler handler = handlers.get(job.type());
        if (job.attempts() > job.maxAttempts()) {
            giveUp(job, handler, "Lease expired too often (attempt " + job.attempts() + ")", true);
            return;
        }
        try {
            JobOutcome outcome = handler.handle(job);
            transactions.executeWithoutResult(status -> {
                store.complete(job.id());
                outcome.followUps().forEach(next -> store.enqueue(next, settings.maxAttempts()));
            });
        } catch (PermanentJobFailure e) {
            log.info("Job {} ({}) failed permanently: {}", job.id(), job.type(), e.getMessage());
            giveUp(job, handler, e.getMessage(), false);
        } catch (Exception e) {
            String error = describe(e);
            if (job.attempts() >= job.maxAttempts()) {
                log.warn("Job {} ({}) is dead after {} attempts: {}", job.id(), job.type(), job.attempts(), error);
                giveUp(job, handler, error, true);
            } else {
                Instant next = Instant.now().plus(backoff(job.attempts()));
                log.info("Job {} ({}) attempt {} failed, retrying at {}: {}", job.id(), job.type(), job.attempts(),
                        next, error);
                transactions.executeWithoutResult(status -> store.retryAt(job.id(), next, error));
            }
        }
    }

    private void giveUp(ClaimedJob job, JobHandler handler, String reason, boolean dead) {
        List<NewJob> reports = handler == null ? List.of() : handler.onGiveUp(job, reason);
        transactions.executeWithoutResult(status -> {
            if (dead) {
                store.dead(job.id(), reason);
            } else {
                store.fail(job.id(), reason);
            }
            reports.forEach(report -> store.enqueue(report, settings.maxAttempts()));
        });
    }

    Duration backoff(int attempt) {
        Duration delay = settings.backoffBase().multipliedBy(1L << Math.min(attempt - 1, 20));
        return delay.compareTo(settings.backoffMax()) > 0 ? settings.backoffMax() : delay;
    }

    /** Class and message only: exception messages of clients never carry document content. */
    static String describe(Throwable e) {
        String message = e.getMessage() == null ? "" : ": " + e.getMessage().replaceAll("\\s+", " ");
        String text = e.getClass().getSimpleName() + message;
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    @Override
    public synchronized void close() {
        if (poller != null) {
            poller.shutdownNow();
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
