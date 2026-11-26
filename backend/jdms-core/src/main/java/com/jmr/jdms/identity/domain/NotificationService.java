package com.jmr.jdms.identity.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.VersionQuarantined;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.tenant.TenantScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/** The caller's notifications, and the events that create them (delivered through the outbox after commit). */
@Service
public class NotificationService {

    public static final String VERSION_QUARANTINED = "VERSION_QUARANTINED";

    public record NotificationView(UUID id, String type, UUID documentId, UUID versionId, String message,
            Instant createdAt, Instant readAt) {

        static NotificationView of(Notification notification) {
            return new NotificationView(notification.getId(), notification.getType(), notification.getDocumentId(),
                    notification.getVersionId(), notification.getMessage(), notification.getCreatedAt(),
                    notification.getReadAt());
        }
    }

    private final NotificationRepository notifications;
    private final UserProfileRepository profiles;
    private final TransactionTemplate transactions;
    private final AuditTrail auditTrail;

    NotificationService(NotificationRepository notifications, UserProfileRepository profiles,
            TransactionTemplate transactions, AuditTrail auditTrail) {
        this.notifications = notifications;
        this.profiles = profiles;
        this.transactions = transactions;
        this.auditTrail = auditTrail;
    }

    /** Tells the uploader that their file failed the antivirus scan (spec §4.2). */
    @Async
    @TransactionalEventListener
    void on(VersionQuarantined event) {
        if (event.uploaderId() == null) {
            return;
        }
        TenantScope.run(event.tenantId(), () -> transactions.executeWithoutResult(status -> {
            // an event redelivered after a failure (EventRedelivery) does not notify twice
            if (profiles.existsById(event.uploaderId()) && !notifications.existsByUserIdAndTypeAndVersionId(
                    event.uploaderId(), VERSION_QUARANTINED, event.versionId())) {
                Notification notification = notifications.save(new Notification(event.uploaderId(),
                        VERSION_QUARANTINED, event.documentId(), event.versionId(),
                        "Your uploaded file failed the antivirus scan and has been quarantined."));
                auditTrail.record(AuditEntry.success("NOTIFICATION_CREATED", "NOTIFICATION", notification.getId(),
                        Map.of("type", VERSION_QUARANTINED, "userId", event.uploaderId().toString(),
                                "documentId", event.documentId().toString())));
            }
        }));
    }

    @Transactional(readOnly = true)
    public Page<NotificationView> mine(Pageable pageable) {
        return notifications.findByUserIdOrderByCreatedAtDesc(RequestContext.require().subjectId(), pageable)
                .map(NotificationView::of);
    }

    @Transactional
    public NotificationView markRead(UUID id) {
        Notification notification = notifications.findByIdAndUserId(id, RequestContext.require().subjectId())
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found."));
        if (notification.getReadAt() == null) {
            notification.markRead(Instant.now().truncatedTo(ChronoUnit.MICROS));
            notifications.flush();
            auditTrail.record(AuditEntry.success("NOTIFICATION_READ", "NOTIFICATION", notification.getId(),
                    Map.of()));
        }
        return NotificationView.of(notification);
    }
}
