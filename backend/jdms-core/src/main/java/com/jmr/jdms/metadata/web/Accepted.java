package com.jmr.jdms.metadata.web;

import com.jmr.jdms.workflow.api.PendingChangeView;
import java.net.URI;
import org.springframework.http.ResponseEntity;

/** Admin writes are proposals: 202 with the pending change and its location (maker-checker, B-02). */
final class Accepted {

    private Accepted() {
    }

    static ResponseEntity<PendingChangeView> of(PendingChangeView change) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/admin/pending-changes/" + change.id()))
                .body(change);
    }
}
