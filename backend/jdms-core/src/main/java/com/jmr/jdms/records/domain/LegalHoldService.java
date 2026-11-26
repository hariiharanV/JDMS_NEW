package com.jmr.jdms.records.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.document.api.DocumentRecords;
import com.jmr.jdms.document.api.IndexableDocuments;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legal holds (spec §4.8): named, with a reason; documents added by explicit selection or by business key; while a
 * document is in any active hold it cannot be deleted or disposed. Records officers ({@code MANAGE_HOLDS}) only;
 * they can hold only documents they can see.
 */
@Service
public class LegalHoldService {

    public static final String HOLD_NOT_FOUND = "LEGAL_HOLD_NOT_FOUND";
    public static final String HOLD_RELEASED = "LEGAL_HOLD_RELEASED";

    public record HoldView(UUID id, String name, String reason, LegalHoldStatus status, List<UUID> documentIds,
            Instant createdAt, String createdBy, UUID releasedBy, Instant releasedAt) {
    }

    /** {@code documentIds} and/or one business key; all matching visible documents are held. */
    public record ItemsRequest(List<UUID> documentIds, String cif, String accountNo, String contractRef) {
    }

    private final LegalHoldRepository holds;
    private final LegalHoldItemRepository items;
    private final DocumentRecords documents;
    private final IndexableDocuments visibility;
    private final AuditTrail auditTrail;

    LegalHoldService(LegalHoldRepository holds, LegalHoldItemRepository items, DocumentRecords documents,
            IndexableDocuments visibility, AuditTrail auditTrail) {
        this.holds = holds;
        this.items = items;
        this.documents = documents;
        this.visibility = visibility;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<HoldView> list() {
        return holds.findAll().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public HoldView get(UUID id) {
        return view(load(id));
    }

    @Transactional
    public HoldView create(String name, String reason) {
        List<Violation> violations = new ArrayList<>();
        if (name == null || name.isBlank() || name.length() > 200) {
            violations.add(new Violation("name", "NAME_INVALID", "Required, at most 200 characters."));
        }
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            violations.add(new Violation("reason", "REASON_REQUIRED", "Required, at most 1000 characters."));
        }
        ValidationFailedException.throwIfAny("The legal hold is invalid.", violations);
        LegalHold hold = holds.save(new LegalHold(name.trim(), reason.trim()));
        auditTrail.record(AuditEntry.success("LEGAL_HOLD_CREATED", "LEGAL_HOLD", hold.getId(),
                Map.of("name", hold.getName())));
        return view(hold);
    }

    @Transactional
    public HoldView addItems(UUID holdId, ItemsRequest request) {
        LegalHold hold = loadActive(holdId);
        Set<UUID> wanted = new LinkedHashSet<>(request.documentIds() == null ? List.of() : request.documentIds());
        wanted.addAll(documents.idsByKeys(blank(request.cif()), blank(request.accountNo()),
                blank(request.contractRef())));
        if (wanted.isEmpty()) {
            throw new ValidationFailedException("Nothing to hold.", List.of(new Violation("documentIds",
                    "DOCUMENTS_REQUIRED", "Give document IDs or a business key that matches documents.")));
        }
        Set<UUID> visible = visibility.permitted(wanted, Action.VIEW_METADATA);
        List<Violation> unknown = wanted.stream().filter(id -> !visible.contains(id))
                .map(id -> new Violation("documentIds", "DOCUMENT_UNKNOWN", "Unknown document " + id)).toList();
        ValidationFailedException.throwIfAny("Some documents cannot be held.", unknown);
        RequestContext caller = RequestContext.require();
        Instant now = now();
        List<UUID> added = new ArrayList<>();
        for (UUID documentId : visible) {
            if (items.findByHoldIdAndDocumentId(holdId, documentId).isEmpty()) {
                items.save(new LegalHoldItem(holdId, documentId, caller.subjectId(), now));
                added.add(documentId);
            }
        }
        items.flush();
        documents.setLegalHold(added, true);
        auditTrail.record(AuditEntry.success("LEGAL_HOLD_ITEMS_ADDED", "LEGAL_HOLD", hold.getId(),
                Map.of("documentIds", added.stream().map(UUID::toString).toList())));
        return view(hold);
    }

    @Transactional
    public HoldView removeItem(UUID holdId, UUID documentId) {
        LegalHold hold = loadActive(holdId);
        LegalHoldItem item = items.findByHoldIdAndDocumentId(holdId, documentId)
                .orElseThrow(() -> ApiException.notFound("LEGAL_HOLD_ITEM_NOT_FOUND", "The document is not held."));
        items.delete(item);
        items.flush();
        refreshFlags(Set.of(documentId));
        auditTrail.record(AuditEntry.success("LEGAL_HOLD_ITEM_REMOVED", "LEGAL_HOLD", hold.getId(),
                Map.of("documentId", documentId.toString())));
        return view(hold);
    }

    @Transactional
    public HoldView release(UUID holdId) {
        LegalHold hold = loadActive(holdId);
        hold.release(RequestContext.require().subjectId(), now());
        holds.flush();
        refreshFlags(items.findByHoldId(holdId).stream().map(LegalHoldItem::getDocumentId)
                .collect(Collectors.toSet()));
        auditTrail.record(AuditEntry.success("LEGAL_HOLD_RELEASED", "LEGAL_HOLD", hold.getId(), Map.of()));
        return view(hold);
    }

    /** A document stays held while any other active hold contains it. */
    private void refreshFlags(Set<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        Set<UUID> stillHeld = items.findHeld(documentIds);
        documents.setLegalHold(documentIds.stream().filter(id -> !stillHeld.contains(id)).toList(), false);
    }

    private HoldView view(LegalHold hold) {
        return new HoldView(hold.getId(), hold.getName(), hold.getReason(), hold.getStatus(),
                items.findByHoldId(hold.getId()).stream().map(LegalHoldItem::getDocumentId).toList(),
                hold.getCreatedAt(), hold.getCreatedBy(), hold.getReleasedBy(), hold.getReleasedAt());
    }

    private LegalHold load(UUID id) {
        return holds.findById(id).orElseThrow(() -> ApiException.notFound(HOLD_NOT_FOUND, "Legal hold not found."));
    }

    private LegalHold loadActive(UUID id) {
        LegalHold hold = load(id);
        if (hold.getStatus() != LegalHoldStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, HOLD_RELEASED, "The legal hold has been released.");
        }
        return hold;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
