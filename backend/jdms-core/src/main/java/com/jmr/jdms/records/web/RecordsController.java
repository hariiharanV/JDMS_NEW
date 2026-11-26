package com.jmr.jdms.records.web;

import com.jmr.jdms.records.domain.DispositionService;
import com.jmr.jdms.records.domain.DispositionService.BatchView;
import com.jmr.jdms.records.domain.LegalHoldService;
import com.jmr.jdms.records.domain.LegalHoldService.HoldView;
import com.jmr.jdms.records.domain.LegalHoldService.ItemsRequest;
import com.jmr.jdms.records.domain.RecordEventService;
import com.jmr.jdms.records.domain.RecordEventService.EventRequest;
import com.jmr.jdms.records.domain.RetentionPolicyAdministration;
import com.jmr.jdms.records.domain.RetentionPolicyAdministration.PolicyPayload;
import com.jmr.jdms.records.domain.RetentionPolicyAdministration.PolicyView;
import com.jmr.jdms.shared.web.ETags;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Records management (spec §4.8, API outline "Records"): retention policies ({@code MANAGE_RETENTION}, maker-checker),
 * legal holds ({@code MANAGE_HOLDS}), disposition batches ({@code DISPOSE}) and retention events.
 */
@RestController
class RecordsController {

    private final RetentionPolicyAdministration policies;
    private final LegalHoldService holds;
    private final DispositionService disposition;
    private final RecordEventService events;

    RecordsController(RetentionPolicyAdministration policies, LegalHoldService holds, DispositionService disposition,
            RecordEventService events) {
        this.policies = policies;
        this.holds = holds;
        this.disposition = disposition;
        this.events = events;
    }

    @GetMapping("/api/v1/retention-policies")
    @PreAuthorize("@access.can('MANAGE_RETENTION')")
    List<PolicyView> policies() {
        return policies.list();
    }

    @GetMapping("/api/v1/retention-policies/{id}")
    @PreAuthorize("@access.can('MANAGE_RETENTION')")
    ResponseEntity<PolicyView> policy(@PathVariable UUID id) {
        PolicyView view = policies.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping("/api/v1/retention-policies")
    @PreAuthorize("@access.can('MANAGE_RETENTION')")
    ResponseEntity<PendingChangeView> createPolicy(@RequestBody PolicyPayload payload,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return accepted(policies.proposeCreate(payload));
    }

    @PutMapping("/api/v1/retention-policies/{id}")
    @PreAuthorize("@access.can('MANAGE_RETENTION')")
    ResponseEntity<PendingChangeView> updatePolicy(@PathVariable UUID id, @RequestBody PolicyPayload payload,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return accepted(policies.proposeUpdate(id, ETags.requiredVersion(ifMatch), payload));
    }

    @GetMapping("/api/v1/legal-holds")
    @PreAuthorize("@access.can('MANAGE_HOLDS')")
    List<HoldView> holds() {
        return holds.list();
    }

    @PostMapping("/api/v1/legal-holds")
    @PreAuthorize("@access.can('MANAGE_HOLDS')")
    ResponseEntity<HoldView> createHold(@RequestBody NewHold request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        HoldView hold = holds.create(request.name(), request.reason());
        return ResponseEntity.created(URI.create("/api/v1/legal-holds/" + hold.id())).body(hold);
    }

    @GetMapping("/api/v1/legal-holds/{id}")
    @PreAuthorize("@access.can('MANAGE_HOLDS')")
    HoldView hold(@PathVariable UUID id) {
        return holds.get(id);
    }

    @PostMapping("/api/v1/legal-holds/{id}/items")
    @PreAuthorize("@access.can('MANAGE_HOLDS')")
    HoldView addItems(@PathVariable UUID id, @RequestBody ItemsRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return holds.addItems(id, request);
    }

    @DeleteMapping("/api/v1/legal-holds/{id}/items/{documentId}")
    @PreAuthorize("@access.can('MANAGE_HOLDS')")
    HoldView removeItem(@PathVariable UUID id, @PathVariable UUID documentId) {
        return holds.removeItem(id, documentId);
    }

    @PostMapping("/api/v1/legal-holds/{id}/release")
    @PreAuthorize("@access.can('MANAGE_HOLDS')")
    HoldView release(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return holds.release(id);
    }

    @GetMapping("/api/v1/disposition-batches")
    @PreAuthorize("@access.can('DISPOSE')")
    List<BatchView> batches() {
        return disposition.list();
    }

    @GetMapping("/api/v1/disposition-batches/{id}")
    @PreAuthorize("@access.can('DISPOSE')")
    BatchView batch(@PathVariable UUID id) {
        return disposition.get(id);
    }

    /** Runs the daily preparation now for the caller's tenant: 201 with the batch, 204 when nothing is due. */
    @PostMapping("/api/v1/disposition-batches/prepare")
    @PreAuthorize("@access.can('DISPOSE')")
    ResponseEntity<BatchView> prepare(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return disposition.prepare().map(batch -> ResponseEntity.created(
                URI.create("/api/v1/disposition-batches/" + batch.id())).body(batch))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/api/v1/disposition-batches/{id}/approve")
    @PreAuthorize("@access.can('DISPOSE')")
    BatchView approve(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return disposition.approve(id);
    }

    @PostMapping("/api/v1/disposition-batches/{id}/reject")
    @PreAuthorize("@access.can('DISPOSE')")
    BatchView reject(@PathVariable UUID id, @RequestBody(required = false) Comment request) {
        return disposition.reject(id, request == null ? null : request.comment());
    }

    /** e.g. {@code {"type": "ACCOUNT_CLOSED", "accountNo": "...", "occurredAt": "..."}}: starts event retention. */
    @PostMapping("/api/v1/records/events")
    @PreAuthorize("@access.can('MANAGE_RETENTION')")
    ResponseEntity<Map<String, UUID>> event(@RequestBody EventRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(201).body(Map.of("id", events.record(request)));
    }

    record NewHold(String name, String reason) {
    }

    record Comment(String comment) {
    }

    private static ResponseEntity<PendingChangeView> accepted(PendingChangeView change) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/admin/pending-changes/" + change.id()))
                .body(change);
    }
}
