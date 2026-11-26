package com.jmr.jdms.records.domain;

import com.jmr.jdms.document.api.RecordDocument;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * When a document's retention ends (spec §4.8): its trigger date plus the policy's period. Creation date, expiry
 * date, or the first matching event for the document's account; no trigger date yet means not due. Dates in UTC.
 */
final class RetentionCalculator {

    /** The first event date for the document's account, if the policy is event-based. */
    interface EventLookup {
        Optional<Instant> firstEvent(String eventType, String accountNo);
    }

    private RetentionCalculator() {
    }

    static Optional<LocalDate> dueDate(RetentionPolicy policy, RecordDocument document, EventLookup events) {
        Optional<LocalDate> start = switch (policy.getTriggerType()) {
            case CREATION_DATE -> Optional.of(date(document.createdAt()));
            case EXPIRY_DATE -> Optional.ofNullable(document.expiryDate());
            case EVENT -> document.accountNo() == null ? Optional.empty()
                    : events.firstEvent(policy.getEventType(), document.accountNo())
                            .map(RetentionCalculator::date);
        };
        return start.map(date -> date.plusMonths(policy.getPeriodMonths()));
    }

    static boolean due(RetentionPolicy policy, RecordDocument document, EventLookup events, LocalDate today) {
        return !document.onLegalHold() && dueDate(policy, document, events).map(date -> !date.isAfter(today))
                .orElse(false);
    }

    private static LocalDate date(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
