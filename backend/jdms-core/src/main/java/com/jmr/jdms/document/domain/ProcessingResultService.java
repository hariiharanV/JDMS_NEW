package com.jmr.jdms.document.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.DocumentChanged;
import com.jmr.jdms.contracts.ProcessingOutcome;
import com.jmr.jdms.contracts.ProcessingResult;
import com.jmr.jdms.contracts.ProcessingStep;
import com.jmr.jdms.contracts.RenditionBlob;
import com.jmr.jdms.contracts.VersionQuarantined;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies the worker's processing results to versions (spec §4.2, ADR-0014): processing status, detected type, page
 * count, renditions, audit, and the quarantine event that notifies the uploader. Idempotent: a result delivered
 * twice changes nothing the second time, and a rendition of a kind the version already has is discarded (its blob is
 * deleted after commit). Runs in the result's tenant, without a request (actor {@code system}).
 */
@Service
public class ProcessingResultService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingResultService.class);

    private final DocumentVersionRepository versions;
    private final RenditionRepository renditions;
    private final ContentStore contentStore;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;

    ProcessingResultService(DocumentVersionRepository versions, RenditionRepository renditions,
            ContentStore contentStore, AuditTrail auditTrail, ApplicationEventPublisher events) {
        this.versions = versions;
        this.renditions = renditions;
        this.contentStore = contentStore;
        this.auditTrail = auditTrail;
        this.events = events;
    }

    @Transactional
    public void apply(ProcessingResult result) {
        DocumentVersion version = versions.lockForUpdate(result.versionId(), result.documentId()).orElse(null);
        if (version == null) {
            log.warn("Processing result for unknown version {}; its renditions are discarded", result.versionId());
            discardAfterCommit(result.renditions().stream().map(RenditionBlob::blobKey).toList());
            return;
        }
        ProcessingStatus before = version.getProcessingStatus();
        if (before == ProcessingStatus.QUARANTINED) {
            discardAfterCommit(result.renditions().stream().map(RenditionBlob::blobKey).toList());
            return; // nothing is derived from an infected file
        }

        List<String> added = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        for (RenditionBlob blob : result.renditions()) {
            Optional<Rendition> existing = renditions.findByVersionIdAndKind(version.getId(), blob.kind());
            if (existing.isPresent()) {
                if (!existing.get().getBlobKey().equals(blob.blobKey())) {
                    duplicates.add(blob.blobKey()); // produced again by a duplicate delivery
                }
            } else {
                renditions.save(new Rendition(version.getId(), blob.kind(), blob.mimeType(),
                        new StoredContent(blob.blobKey(), blob.sha256(), blob.sizeBytes(), blob.wrappedKey(),
                                blob.keyRef()), result.ocr()));
                added.add(blob.kind());
            }
        }
        discardAfterCommit(duplicates);
        version.describeContent(result.detectedMimeType(), result.pageCount());

        switch (result.outcome()) {
            case INFECTED -> {
                version.quarantine();
                events.publishEvent(new VersionQuarantined(result.tenantId(), result.documentId(), version.getId(),
                        uploader(version), result.detail()));
            }
            case FAILED -> version.processingFinished(true);
            case CLEAN, DONE -> {
                if (result.last()) {
                    version.processingFinished(false);
                } else {
                    version.processingStarted();
                }
            }
        }
        versions.flush();
        if (version.getProcessingStatus() != before || !added.isEmpty()) {
            audit(result, version, added);
            events.publishEvent(new DocumentChanged(result.tenantId(), result.documentId()));
        }
    }

    private void audit(ProcessingResult result, DocumentVersion version, List<String> added) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("versionId", version.getId().toString());
        details.put("step", result.step().name());
        details.put("outcome", result.outcome().name());
        details.put("processingStatus", version.getProcessingStatus().name());
        if (!added.isEmpty()) {
            details.put("renditions", added);
        }
        if (result.detail() != null) {
            details.put(result.outcome() == ProcessingOutcome.INFECTED ? "signature" : "reason", result.detail());
        }
        String action = result.outcome() == ProcessingOutcome.INFECTED ? "VERSION_QUARANTINED"
                : result.outcome() == ProcessingOutcome.FAILED ? "VERSION_PROCESSING_FAILED"
                : result.last() ? "VERSION_PROCESSED"
                : result.step() == ProcessingStep.SCAN ? "VERSION_SCANNED" : "VERSION_RENDITIONS_ADDED";
        auditTrail.record(AuditEntry.success(action, DocumentAuditActions.RESOURCE_DOCUMENT,
                version.getDocumentId(), details));
    }

    private static UUID uploader(DocumentVersion version) {
        try {
            return UUID.fromString(version.getCreatedBy());
        } catch (IllegalArgumentException e) {
            return null; // created by the system (e.g. migration)
        }
    }

    private void discardAfterCommit(List<String> blobKeys) {
        if (blobKeys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                blobKeys.forEach(key -> {
                    try {
                        contentStore.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("Could not delete a discarded rendition blob; the reconciler will find it");
                    }
                });
            }
        });
    }
}
