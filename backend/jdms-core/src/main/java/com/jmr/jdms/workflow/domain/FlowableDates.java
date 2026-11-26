package com.jmr.jdms.workflow.domain;

import java.time.Instant;
import java.util.Date;

/** Flowable's task API speaks {@link Date}; this is the only place that converts (everything else uses java.time). */
final class FlowableDates {

    private FlowableDates() {
    }

    static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }

    static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }
}
