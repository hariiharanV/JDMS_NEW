package com.jmr.jdms.records.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditOutcome;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.document.api.DocumentRecords;
import com.jmr.jdms.document.api.RecordDocument;
import com.jmr.jdms.metadata.api.RetentionAssignments;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.TenantDirectory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disposition (spec §4.8): a daily job lists the documents whose retention has ended and that no hold protects, in a
 * batch per tenant; a records officer approves the batch, and each document is re-checked, its blobs destroyed and
 * its metadata reduced to a tombstone kept as evidence. Every step is audited.
 */
@Service
public class DispositionService {

    public static final String BATCH_NOT_FOUND = "DISPOSITION_BATCH_NOT_FOUND";
    public static final String BATCH_NOT_PENDING = "DISPOSITION_BATCH_NOT_PENDING";
    private static final Logger log = LoggerFactory.getLogger(DispositionService.class);
    private static final int PAGE = 500;

    public record ItemView(UUID documentId, UUID retentionPolicyId, LocalDate dueDate, ItemOutcome outcome,
            String reason) {
    }

    public record BatchView(UUID id, BatchStatus status, Instant preparedAt, UUID approvedBy, Instant approvedAt,
            Instant executedAt, List<ItemView> items) {
    }

    private final RetentionPolicyRepository policies;
    private final RetentionAssignments assignments;
    private final RecordEventRepository events;
    private final DispositionBatchRepository batches;
    private final DispositionItemRepository items;
    private final TombstoneRepository tombstones;
    private final DocumentRecords documents;
    private final TenantDirectory tenants;
    private final AuditTrail auditTrail;
    private final TransactionTemplate transactions;

    DispositionService(RetentionPolicyRepository policies, RetentionAssignments assignments,
            RecordEventRepository events, DispositionBatchRepository batches, DispositionItemRepository items,
            TombstoneRepository tombstones, DocumentRecords documents, TenantDirectory tenants, AuditTrail auditTrail,
            TransactionTemplate transactions) {
        this.policies = policies;
        this.assignments = assignments;
        this.events = events;
        this.batches = batches;
        this.items = items;
        this.tombstones = tombstones;
        this.documents = documents;
        this.tenants = tenants;
        this.auditTrail = auditTrail;
        this.transactions = transactions;
    }

    /** The daily job: a batch per tenant with documents due. */
    @Scheduled(cron = "${jdms.records.disposition.cron:0 0 3 * * *}")
    void daily() {
        for (var tenant : tenants.findAllActive()) {
            try {
                TenantScope.run(tenant.id(), this::prepare);
            } catch (RuntimeException e) {
                log.warn("Disposition preparation failed for tenant {}: {}", tenant.code(), e.toString());
            }
        }
    }

    /** Lists the current tenant's due documents in a new pending batch; empty when nothing is due. */
    public Optional<BatchView> prepare() {
        return transactions.execute(status -> {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            Set<UUID> alreadyPending = items.findPendingDocumentIds();
            DispositionBatch batch = null;
            int count = 0;
            for (RetentionPolicy policy : policies.findAll()) {
                Set<UUID> types = assignments.typesOf(policy.getId());
                UUID after = null;
                List<RecordDocument> page;
                do {
                    page = documents.ofTypes(types, after, PAGE);
                    for (RecordDocument document : page) {
                        Optional<LocalDate> dueDate = RetentionCalculator.dueDate(policy, document, this::firstEvent);
                        boolean due = RetentionCalculator.due(policy, document, this::firstEvent, today);
                        if (due && !alreadyPending.contains(document.id())) {
                            if (batch == null) {
                                batch = batches.save(new DispositionBatch(now()));
                            }
                            items.save(new DispositionItem(batch.getId(), document.id(), policy.getId(),
                                    dueDate.orElseThrow()));
                            alreadyPending.add(document.id());
                            count++;
                        }
                    }
                    after = page.isEmpty() ? after : page.getLast().id();
                } while (page.size() == PAGE);
            }
            if (batch == null) {
                auditTrail.record(new AuditEntry("DISPOSITION_NONE_DUE", "DISPOSITION_BATCH", null,
                        AuditOutcome.SUCCESS, Map.of(), null, null));
                return Optional.<BatchView>empty();
            }
            items.flush();
            auditTrail.record(AuditEntry.success("DISPOSITION_PREPARED", "DISPOSITION_BATCH", batch.getId(),
                    Map.of("documents", count)));
            return Optional.of(view(batch));
        });
    }

    public List<BatchView> list() {
        return transactions.execute(status -> batches.findAllByOrderByPreparedAtDesc().stream().map(this::view)
                .toList());
    }

    public BatchView get(UUID id) {
        return transactions.execute(status -> view(load(id)));
    }

    /**
     * Approves and executes the batch. Each document is checked again (a hold placed or retention changed since the
     * batch was prepared skips it); disposing one never stops the others.
     */
    public BatchView approve(UUID batchId) {
        UUID approver = RequestContext.require().subjectId();
        List<DispositionItem> pending = transactions.execute(status -> {
            DispositionBatch batch = loadPending(batchId);
            batch.approve(approver, now());
            return items.findByBatchIdOrderByDueDateAscIdAsc(batch.getId());
        });
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        for (DispositionItem item : pending) {
            ItemOutcome outcome = execute(item, batchId, approver, today);
            outcomes.merge(outcome.name(), 1, Integer::sum);
        }
        return transactions.execute(status -> {
            DispositionBatch batch = load(batchId);
            batch.executed(now());
            batches.flush();
            Map<String, Object> details = new LinkedHashMap<>(outcomes);
            auditTrail.record(AuditEntry.success("DISPOSITION_EXECUTED", "DISPOSITION_BATCH", batchId, details));
            return view(batch);
        });
    }

    public BatchView reject(UUID batchId, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new ValidationFailedException("A comment is required.", List.of(new Violation("comment",
                    "COMMENT_REQUIRED", "Say why the batch is rejected.")));
        }
        return transactions.execute(status -> {
            DispositionBatch batch = loadPending(batchId);
            batch.reject(RequestContext.require().subjectId(), now());
            items.findByBatchIdOrderByDueDateAscIdAsc(batchId).forEach(item -> item.settle(ItemOutcome.SKIPPED,
                    "Batch rejected"));
            batches.flush();
            auditTrail.record(AuditEntry.success("DISPOSITION_REJECTED", "DISPOSITION_BATCH", batchId,
                    Map.of("comment", comment.strip())));
            return view(batch);
        });
    }

    private ItemOutcome execute(DispositionItem item, UUID batchId, UUID approver, LocalDate today) {
        Optional<String> skip = transactions.execute(status -> {
            RecordDocument document = documents.find(item.getDocumentId()).orElse(null);
            RetentionPolicy policy = policies.findById(item.getRetentionPolicyId()).orElse(null);
            if (document == null || "DISPOSED".equals(document.status())) {
                return Optional.of("Already disposed");
            }
            if (document.onLegalHold()) {
                return Optional.of("On legal hold");
            }
            if (policy == null || !assignments.typesOf(policy.getId()).contains(document.documentTypeId())
                    || !RetentionCalculator.due(policy, document, this::firstEvent, today)) {
                return Optional.of("No longer due");
            }
            return Optional.<String>empty();
        });
        if (skip.isPresent()) {
            settle(item.getId(), ItemOutcome.SKIPPED, skip.get());
            return ItemOutcome.SKIPPED;
        }
        RecordDocument document = transactions.execute(status -> documents.find(item.getDocumentId()).orElseThrow());
        try {
            List<String> hashes = documents.dispose(item.getDocumentId());
            transactions.executeWithoutResult(status -> {
                tombstones.save(new Tombstone(document.id(), document.documentTypeId(), document.createdAt(), now(),
                        batchId, approver, hashes));
                items.findById(item.getId()).orElseThrow().settle(ItemOutcome.DISPOSED, null);
                auditTrail.record(AuditEntry.success("RECORD_DISPOSED", "DOCUMENT", document.id(),
                        Map.of("batchId", batchId.toString(), "versions", hashes.size())));
            });
            return ItemOutcome.DISPOSED;
        } catch (RuntimeException e) {
            log.warn("Disposition of document {} failed: {}", document.id(), e.toString());
            settle(item.getId(), ItemOutcome.FAILED, e.getClass().getSimpleName());
            return ItemOutcome.FAILED;
        }
    }

    private void settle(UUID itemId, ItemOutcome outcome, String reason) {
        transactions.executeWithoutResult(status -> items.findById(itemId).orElseThrow().settle(outcome, reason));
    }

    private Optional<Instant> firstEvent(String eventType, String accountNo) {
        return events.findFirstByEventTypeAndAccountNoOrderByOccurredAtAsc(eventType, accountNo)
                .map(RecordEvent::getOccurredAt);
    }

    private BatchView view(DispositionBatch batch) {
        List<ItemView> views = new ArrayList<>();
        items.findByBatchIdOrderByDueDateAscIdAsc(batch.getId()).forEach(item -> views.add(new ItemView(
                item.getDocumentId(), item.getRetentionPolicyId(), item.getDueDate(), item.getOutcome(),
                item.getOutcomeReason())));
        return new BatchView(batch.getId(), batch.getStatus(), batch.getPreparedAt(), batch.getApprovedBy(),
                batch.getApprovedAt(), batch.getExecutedAt(), views);
    }

    private DispositionBatch load(UUID id) {
        return batches.findById(id).orElseThrow(() -> ApiException.notFound(BATCH_NOT_FOUND,
                "Disposition batch not found."));
    }

    private DispositionBatch loadPending(UUID id) {
        DispositionBatch batch = batches.findForDecision(id).orElseThrow(() -> ApiException.notFound(BATCH_NOT_FOUND,
                "Disposition batch not found."));
        if (batch.getStatus() != BatchStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, BATCH_NOT_PENDING, "The batch has already been decided.");
        }
        return batch;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
