package com.jmr.jdms.perf.generate;

import com.jmr.jdms.perf.config.PerfProperties;
import com.jmr.jdms.perf.generate.SyntheticCorpus.SyntheticDocument;
import com.jmr.jdms.perf.infra.PerfDataWriter;
import com.jmr.jdms.perf.infra.PerfDataWriter.Catalog;
import com.jmr.jdms.perf.infra.PerfDataWriter.Row;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.platform.ids.TimeOrderedIds;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fills the PERF tenant up to {@code jdms.perf.documents} synthetic documents, batch by batch: the two blobs of every
 * document (original and extracted text, the same UTF-8 text) are encrypted and uploaded concurrently, then the batch
 * is inserted in one transaction. A batch that fails leaves no rows (its blobs become orphans); a re-run continues
 * from the current count. Documents are searchable after a rebuild of the tenant's index (docs/perf/README.md).
 */
@Service
public class DocumentGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocumentGenerator.class);
    private static final long MAX_BYTES = 1024 * 1024;

    private final PerfDataWriter writer;
    private final EnvelopeEncryption encryption;
    private final PerfProperties properties;
    private final SyntheticCorpus corpus;

    DocumentGenerator(PerfDataWriter writer, EnvelopeEncryption encryption, PerfProperties properties) {
        this.writer = writer;
        this.encryption = encryption;
        this.properties = properties;
        this.corpus = new SyntheticCorpus(properties.seed(), properties.customers(), properties.arabicShare());
    }

    public record Summary(long before, long written, long after, Duration elapsed) {

        public double perHour() {
            return elapsed.isZero() ? 0 : written * 3_600_000.0 / elapsed.toMillis();
        }
    }

    public Summary generate() {
        writer.seed();
        Catalog catalog = writer.catalog(properties.tenantCode());
        long before = writer.count(catalog.tenantId());
        long started = System.nanoTime();
        long next = before;
        Semaphore permits = new Semaphore(properties.concurrency());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (next < properties.documents()) {
                int size = (int) Math.min(properties.batchSize(), properties.documents() - next);
                List<Future<Row>> pending = new ArrayList<>(size);
                for (long index = next; index < next + size; index++) {
                    SyntheticDocument document = corpus.document(index);
                    pending.add(executor.submit(() -> row(catalog, document, permits)));
                }
                List<Row> rows = new ArrayList<>(size);
                for (Future<Row> future : pending) {
                    rows.add(future.get());
                }
                writer.insert(catalog, rows);
                next += size;
                long elapsedMs = Math.max(1, (System.nanoTime() - started) / 1_000_000);
                log.info("Generated {} of {} documents ({} per hour)", next, properties.documents(),
                        (next - before) * 3_600_000L / elapsedMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Generation interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("A blob could not be stored", e.getCause());
        }
        long after = writer.count(catalog.tenantId());
        return new Summary(before, after - before, after, Duration.ofNanos(System.nanoTime() - started));
    }

    private Row row(Catalog catalog, SyntheticDocument document, Semaphore permits) throws InterruptedException {
        byte[] text = document.text().getBytes(StandardCharsets.UTF_8);
        StoredBlob original = store(catalog, text, permits);
        StoredBlob extracted = store(catalog, text, permits);
        return new Row(TimeOrderedIds.next(), TimeOrderedIds.next(), TimeOrderedIds.next(), document, original,
                extracted);
    }

    private StoredBlob store(Catalog catalog, byte[] content, Semaphore permits) throws InterruptedException {
        permits.acquire();
        try {
            return encryption.store(catalog.tenantId(), properties.tenantCode(), new ByteArrayInputStream(content),
                    MAX_BYTES);
        } finally {
            permits.release();
        }
    }
}
