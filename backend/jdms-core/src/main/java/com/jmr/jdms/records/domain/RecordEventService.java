package com.jmr.jdms.records.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business events that start event-based retention (spec §4.8), e.g. {@code ACCOUNT_CLOSED} from core banking. */
@Service
public class RecordEventService {

    static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");

    private final RecordEventRepository events;
    private final AuditTrail auditTrail;

    RecordEventService(RecordEventRepository events, AuditTrail auditTrail) {
        this.events = events;
        this.auditTrail = auditTrail;
    }

    public record EventRequest(String type, String accountNo, Instant occurredAt) {
    }

    @Transactional
    public UUID record(EventRequest request) {
        List<Violation> violations = new ArrayList<>();
        if (request.type() == null || !TYPE.matcher(request.type()).matches()) {
            violations.add(new Violation("type", "EVENT_TYPE_INVALID", "e.g. ACCOUNT_CLOSED"));
        }
        if (request.accountNo() == null || request.accountNo().isBlank() || request.accountNo().length() > 50) {
            violations.add(new Violation("accountNo", "ACCOUNT_REQUIRED", "The account the event concerns."));
        }
        if (request.occurredAt() == null || request.occurredAt().isAfter(Instant.now().plusSeconds(300))) {
            violations.add(new Violation("occurredAt", "OCCURRED_AT_INVALID", "Required, not in the future."));
        }
        ValidationFailedException.throwIfAny("The event is invalid.", violations);
        RecordEvent event = events.save(new RecordEvent(request.type(), request.accountNo().strip(),
                request.occurredAt()));
        auditTrail.record(AuditEntry.success("RECORD_EVENT_RECEIVED", "RECORD_EVENT", event.getId(),
                Map.of("type", event.getEventType(), "accountNo", event.getAccountNo())));
        return event.getId();
    }
}
