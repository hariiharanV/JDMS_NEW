package com.jmr.jdms.document.infra;

import com.jmr.jdms.document.domain.CaptureRepository;
import com.jmr.jdms.document.domain.DocumentVersionRepository;
import com.jmr.jdms.document.domain.RenditionRepository;
import com.jmr.jdms.platform.content.BlobStore;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.TenantDirectory;
import com.jmr.jdms.tenant.api.TenantInfo;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds blobs that no version, rendition or capture references (an upload whose metadata transaction failed after the blob was written,
 * spec "blob first, then metadata"). Blobs younger than the grace period are skipped, as their transaction may still
 * be running. Orphans are reported (log and metric) and, when configured, deleted.
 */
@Component
public class OrphanBlobReconciler {

    private static final Logger log = LoggerFactory.getLogger(OrphanBlobReconciler.class);

    private final BlobStore blobs;
    private final DocumentVersionRepository versions;
    private final RenditionRepository renditions;
    private final CaptureRepository captures;
    private final TenantDirectory tenants;
    private final TransactionTemplate transactions;
    private final StorageProperties properties;
    private final MeterRegistry meters;

    OrphanBlobReconciler(BlobStore blobs, DocumentVersionRepository versions, RenditionRepository renditions,
            CaptureRepository captures, TenantDirectory tenants, TransactionTemplate transactions, StorageProperties properties,
            MeterRegistry meters) {
        this.blobs = blobs;
        this.versions = versions;
        this.renditions = renditions;
        this.captures = captures;
        this.tenants = tenants;
        this.transactions = transactions;
        this.properties = properties;
        this.meters = meters;
    }

    public record Report(int checked, List<String> orphans, boolean deleted) {
    }

    @Scheduled(cron = "${jdms.storage.reconcile.cron:0 30 2 * * *}")
    void scheduled() {
        reconcile();
    }

    public Report reconcile() {
        return reconcile(properties.reconcile().grace(), properties.reconcile().deleteOrphans());
    }

    public Report reconcile(java.time.Duration grace, boolean delete) {
        Instant cutoff = Instant.now().minus(grace);
        Map<String, TenantInfo> tenantsByCode = tenants.findAllActive().stream()
                .collect(Collectors.toMap(TenantInfo::code, Function.identity()));
        List<String> orphans = new ArrayList<>();
        int[] checked = {0};
        blobs.forEach(blob -> {
            if (blob.lastModified().isAfter(cutoff)) {
                return;
            }
            checked[0]++;
            String tenantCode = blob.key().split("/", 2)[0];
            boolean referenced = Optional.ofNullable(tenantsByCode.get(tenantCode))
                    .map(tenant -> TenantScope.call(tenant.id(), () -> Boolean.TRUE.equals(
                            transactions.execute(status -> versions.existsByBlobKey(blob.key())
                                    || renditions.existsByBlobKey(blob.key())
                                    || captures.referencesBlob(blob.key())))))
                    .orElse(false);
            if (!referenced) {
                orphans.add(blob.key());
            }
        });
        if (delete) {
            orphans.forEach(blobs::delete);
        }
        meters.counter("jdms.storage.orphan.blobs").increment(orphans.size());
        if (!orphans.isEmpty()) {
            log.warn("Found {} orphan blob(s) older than {}{}", orphans.size(), grace,
                    delete ? "; deleted" : "; not deleted (jdms.storage.reconcile.delete-orphans=false)");
        }
        return new Report(checked[0], orphans, delete);
    }
}
