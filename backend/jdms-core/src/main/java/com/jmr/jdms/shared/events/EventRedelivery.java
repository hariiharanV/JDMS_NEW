package com.jmr.jdms.shared.events;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redelivers outbox events whose listener failed (B-13). A listener can fail after its transaction committed (the
 * database or the pool briefly unavailable): the publication stays incomplete, and without redelivery the work it
 * stands for (a SCAN job, an index update) would never happen. Spring Modulith marks publications that stay published
 * or in processing too long as failed ({@code spring.modulith.events.staleness.*}); this resubmits failed ones once a
 * minute. Every listener tolerates a second delivery.
 */
@Component
class EventRedelivery {

    private final IncompleteEventPublications publications;
    private final Duration minAge;

    EventRedelivery(IncompleteEventPublications publications,
            @Value("${jdms.events.redelivery-min-age:PT1M}") Duration minAge) {
        this.publications = publications;
        this.minAge = minAge;
    }

    @Scheduled(cron = "${jdms.events.redelivery-cron:30 * * * * *}")
    void redeliverFailed() {
        publications.resubmitIncompletePublications(ResubmissionOptions.defaults().withMinAge(minAge)
                .withBatchSize(200));
    }
}
