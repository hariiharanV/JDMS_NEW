package com.jmr.jdms.document.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditOutcome;
import com.jmr.jdms.audit.api.AuditTrail;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a failed integrity check of stored content: the version becomes {@code MISSING}, an audit event and an
 * ERROR log line (the alert) are written and a metric is incremented. Runs in its own transaction so the record
 * survives the failed request.
 */
@Component
class StorageIntegrityReporter {

    private static final Logger log = LoggerFactory.getLogger(StorageIntegrityReporter.class);

    private final DocumentVersionRepository versions;
    private final AuditTrail auditTrail;
    private final MeterRegistry meters;

    StorageIntegrityReporter(DocumentVersionRepository versions, AuditTrail auditTrail, MeterRegistry meters) {
        this.versions = versions;
        this.auditTrail = auditTrail;
        this.meters = meters;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void report(UUID documentId, UUID versionId, String reason) {
        versions.findById(versionId).ifPresent(DocumentVersion::markMissing);
        auditTrail.record(new AuditEntry(DocumentAuditActions.STORAGE_INTEGRITY_FAILED,
                DocumentAuditActions.RESOURCE_DOCUMENT, documentId.toString(), AuditOutcome.ERROR,
                Map.of("versionId", versionId.toString(), "reason", reason), null, null));
        meters.counter("jdms.storage.integrity.failures").increment();
        log.error("ALERT storage integrity failure: document {} version {} marked MISSING ({})", documentId,
                versionId, reason);
    }
}
