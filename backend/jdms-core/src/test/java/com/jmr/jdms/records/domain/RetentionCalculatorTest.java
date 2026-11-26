package com.jmr.jdms.records.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.document.api.RecordDocument;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetentionCalculatorTest {

    static final Instant CREATED = Instant.parse("2020-03-15T10:00:00Z");
    static final LocalDate TODAY = LocalDate.parse("2026-10-25");

    @Test
    void creationDatePlusPeriod() {
        RetentionPolicy policy = policy(RetentionTrigger.CREATION_DATE, null, 60);
        assertThat(RetentionCalculator.dueDate(policy, document(null, null, false), none()))
                .contains(LocalDate.parse("2025-03-15"));
        assertThat(RetentionCalculator.due(policy, document(null, null, false), none(), TODAY)).isTrue();
        assertThat(RetentionCalculator.due(policy(RetentionTrigger.CREATION_DATE, null, 120),
                document(null, null, false), none(), TODAY)).isFalse();
    }

    @Test
    void expiryDateMissingMeansNotDue() {
        RetentionPolicy policy = policy(RetentionTrigger.EXPIRY_DATE, null, 12);
        assertThat(RetentionCalculator.dueDate(policy, document(null, null, false), none())).isEmpty();
        assertThat(RetentionCalculator.dueDate(policy, document(LocalDate.parse("2024-01-31"), null, false), none()))
                .contains(LocalDate.parse("2025-01-31"));
    }

    @Test
    void eventsStartTheClockForTheAccount() {
        RetentionPolicy policy = policy(RetentionTrigger.EVENT, "ACCOUNT_CLOSED", 0);
        RecordDocument document = document(null, "ACC-1", false);
        assertThat(RetentionCalculator.due(policy, document, none(), TODAY)).isFalse();
        RetentionCalculator.EventLookup closed = (type, account) -> type.equals("ACCOUNT_CLOSED")
                && account.equals("ACC-1") ? Optional.of(Instant.parse("2026-10-01T00:00:00Z")) : Optional.empty();
        assertThat(RetentionCalculator.due(policy, document, closed, TODAY)).isTrue();
        assertThat(RetentionCalculator.due(policy, document(null, "ACC-2", false), closed, TODAY)).isFalse();
    }

    @Test
    void heldDocumentsAreNeverDue() {
        assertThat(RetentionCalculator.due(policy(RetentionTrigger.CREATION_DATE, null, 0),
                document(null, null, true), none(), TODAY)).isFalse();
    }

    static RetentionPolicy policy(RetentionTrigger trigger, String event, int months) {
        return new RetentionPolicy(UUID.randomUUID(), "P", "Policy", trigger, event, months, DispositionAction.DESTROY,
                true);
    }

    static RecordDocument document(LocalDate expiry, String account, boolean held) {
        return new RecordDocument(UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", CREATED, expiry, account, held,
                false);
    }

    static RetentionCalculator.EventLookup none() {
        return (type, account) -> Optional.empty();
    }
}
