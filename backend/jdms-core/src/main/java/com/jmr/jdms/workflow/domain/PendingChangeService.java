package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ErrorCodes;
import com.jmr.jdms.workflow.api.ApplyResult;
import com.jmr.jdms.workflow.api.ApprovalDecision;
import com.jmr.jdms.workflow.api.ApprovalProcesses;
import com.jmr.jdms.workflow.api.ApprovalRequest;
import com.jmr.jdms.workflow.api.ChangeHandler;
import com.jmr.jdms.workflow.api.ChangeProposal;
import com.jmr.jdms.workflow.api.ChangeRequests;
import com.jmr.jdms.workflow.api.PendingChangeStatus;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maker-checker for administrative changes. Rules that hold for every subject type: the proposer can never approve
 * or reject their own change (they may cancel it); the approver must be allowed by the handler's policy check and
 * not blocked by its separation-of-duties rules; a subject has at most one pending change. A change is visible to
 * those who may decide it and to its proposer.
 */
@Service
public class PendingChangeService implements ChangeRequests {

    public static final String CHANGE_PENDING = "CHANGE_PENDING";
    public static final String CHANGE_NOT_FOUND = "CHANGE_NOT_FOUND";
    public static final String CHANGE_NOT_PENDING = "CHANGE_NOT_PENDING";
    public static final String CHANGE_OWN_PROPOSAL = "CHANGE_OWN_PROPOSAL";
    public static final String CHANGE_APPROVER_EXCLUDED = "CHANGE_APPROVER_EXCLUDED";
    public static final String CHANGE_STALE = "CHANGE_STALE";

    private static final String RESOURCE = "PENDING_CHANGE";

    private final PendingChangeRepository changes;
    private final ObjectProvider<ChangeHandler> handlerProvider;
    private final ApprovalEngine approvals;
    private final AuditTrail auditTrail;
    private final JsonMapper jsonMapper;
    private volatile Map<String, ChangeHandler> handlers;

    /** Handlers are looked up lazily: they depend on {@link ChangeRequests}, which this service implements. */
    PendingChangeService(PendingChangeRepository changes, ObjectProvider<ChangeHandler> handlerProvider,
            ApprovalEngine approvals, AuditTrail auditTrail, JsonMapper jsonMapper) {
        this.changes = changes;
        this.handlerProvider = handlerProvider;
        this.approvals = approvals;
        this.auditTrail = auditTrail;
        this.jsonMapper = jsonMapper;
    }

    private Map<String, ChangeHandler> handlers() {
        Map<String, ChangeHandler> current = handlers;
        if (current == null) {
            current = handlerProvider.orderedStream()
                    .collect(Collectors.toUnmodifiableMap(ChangeHandler::subjectType, Function.identity()));
            handlers = current;
        }
        return current;
    }

    @Override
    @Transactional
    public PendingChangeView submit(ChangeProposal proposal) {
        if (!handlers().containsKey(proposal.subjectType())) {
            throw new IllegalArgumentException("No change handler for " + proposal.subjectType());
        }
        boolean subjectBusy = changes.existsBySubjectTypeAndSubjectIdAndStatus(proposal.subjectType(),
                proposal.subjectId(), PendingChangeStatus.PENDING);
        boolean keyBusy = proposal.subjectKey() != null && changes.existsBySubjectTypeAndSubjectKeyAndStatus(
                proposal.subjectType(), proposal.subjectKey(), PendingChangeStatus.PENDING);
        if (subjectBusy || keyBusy) {
            throw new ApiException(HttpStatus.CONFLICT, CHANGE_PENDING,
                    "A change to this item is already waiting for approval.");
        }

        RequestContext caller = RequestContext.require();
        PendingChange change = changes.saveAndFlush(new PendingChange(proposal.subjectType(), proposal.operation(),
                proposal.subjectId(), proposal.subjectKey(), proposal.baseVersion(),
                jsonMapper.writeValueAsString(proposal.payload()), proposal.summary(), caller.subjectId(), now()));
        audit("CHANGE_PROPOSED", change, Map.of());
        // Routed through single-approval so the change appears in approvers' task inboxes (ADR-0013)
        ChangeHandler handler = handlers().get(proposal.subjectType());
        PendingChangeView proposed = view(change);
        ApprovalRequest approval = new ApprovalRequest(ApprovalProcesses.SINGLE, PendingChangeSubjects.SUBJECT,
                change.getId(), handler.documentId(proposed), null, proposal.summary());
        change.attachProcess(approvals.start(approval, handler.historyType(), handler.candidateRoles()));
        return view(change);
    }

    /** The caller's view of the queue, filtered by visibility and paged in memory (admin queues are small). */
    @Transactional(readOnly = true)
    public Page<PendingChangeView> list(PendingChangeStatus status, Pageable pageable) {
        List<PendingChangeView> visible = (status == null ? changes.findAll() : changes.findByStatus(status)).stream()
                .sorted(Comparator.comparing(PendingChange::getProposedAt).reversed())
                .map(this::view).filter(this::visible).toList();
        int from = (int) Math.min(pageable.getOffset(), visible.size());
        int to = Math.min(from + pageable.getPageSize(), visible.size());
        return new PageImpl<>(visible.subList(from, to), pageable, visible.size());
    }

    /** Changes the caller may neither decide nor has proposed are reported as not found. */
    @Transactional(readOnly = true)
    public PendingChangeView get(UUID id) {
        return view(load(id));
    }

    @Transactional
    public Decision approve(UUID id, String comment) {
        PendingChange change = loadPending(id);
        ChangeHandler handler = handlers().get(change.getSubjectType());
        RequestContext caller = RequestContext.require();
        if (caller.subjectId().equals(change.getProposedBy())) {
            throw ApiException.forbidden(CHANGE_OWN_PROPOSAL, "You cannot approve a change you proposed.");
        }
        PendingChangeView before = view(change);
        requireDecider(handler, before, caller);

        ApplyResult result = handler.apply(before);
        if (result.applied()) {
            change.decide(PendingChangeStatus.APPROVED, caller.subjectId(), now(), comment);
            changes.saveAndFlush(change);
            audit("CHANGE_APPROVED", change, Map.of());
            approvals.decideChange(change.getProcessInstanceId(), ApprovalDecision.APPROVE, comment);
            return new Decision(view(change), null);
        }
        change.decide(PendingChangeStatus.STALE, caller.subjectId(), now(), result.staleReason());
        changes.saveAndFlush(change);
        audit("CHANGE_STALE", change, Map.of("reason", result.staleReason()));
        approvals.abortChange(change.getProcessInstanceId(), "STALE");
        return new Decision(view(change), result.staleReason());
    }

    @Transactional
    public PendingChangeView reject(UUID id, String comment) {
        if (comment == null || comment.isBlank()) {
            throw ApiException.unprocessable(ErrorCodes.VALIDATION_FAILED, "A comment is required to reject a change.");
        }
        PendingChange change = loadPending(id);
        RequestContext caller = RequestContext.require();
        if (caller.subjectId().equals(change.getProposedBy())) {
            throw ApiException.forbidden(CHANGE_OWN_PROPOSAL,
                    "You cannot reject a change you proposed; cancel it instead.");
        }
        requireDecider(handlers().get(change.getSubjectType()), view(change), caller);
        change.decide(PendingChangeStatus.REJECTED, caller.subjectId(), now(), comment.trim());
        changes.saveAndFlush(change);
        audit("CHANGE_REJECTED", change, Map.of());
        approvals.decideChange(change.getProcessInstanceId(), ApprovalDecision.REJECT, comment.trim());
        return view(change);
    }

    @Transactional
    public PendingChangeView cancel(UUID id) {
        PendingChange change = loadPending(id);
        RequestContext caller = RequestContext.require();
        if (!caller.subjectId().equals(change.getProposedBy())) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED, "Only the proposer can cancel a change.");
        }
        change.decide(PendingChangeStatus.CANCELLED, caller.subjectId(), now(), null);
        changes.saveAndFlush(change);
        audit("CHANGE_CANCELLED", change, Map.of());
        approvals.abortChange(change.getProcessInstanceId(), "CANCELLED");
        return view(change);
    }

    /** Whether the caller could approve or reject this change now. */
    public boolean canDecide(PendingChangeView change) {
        RequestContext caller = RequestContext.require();
        ChangeHandler handler = handlers().get(change.subjectType());
        return change.status() == PendingChangeStatus.PENDING && handler != null
                && !caller.subjectId().equals(change.proposedBy()) && handler.canDecide(change)
                && handler.approvalBlocker(change, caller).isEmpty();
    }

    /** Whether the caller may see the change (decide it, or proposed it). */
    @Transactional(readOnly = true)
    public boolean isVisible(UUID id) {
        return changes.findById(id).map(change -> visible(view(change))).orElse(false);
    }

    /** Whether the caller could approve or reject the change now. */
    @Transactional(readOnly = true)
    public boolean canDecide(UUID id) {
        return changes.findById(id).map(change -> canDecide(view(change))).orElse(false);
    }

    private void requireDecider(ChangeHandler handler, PendingChangeView change, RequestContext caller) {
        if (!handler.canDecide(change)) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED, "You are not allowed to decide this change.");
        }
        handler.approvalBlocker(change, caller).ifPresent(reason -> {
            throw ApiException.forbidden(CHANGE_APPROVER_EXCLUDED, reason);
        });
    }

    private boolean visible(PendingChangeView change) {
        ChangeHandler handler = handlers().get(change.subjectType());
        return RequestContext.require().subjectId().equals(change.proposedBy())
                || (handler != null && handler.canDecide(change));
    }

    private PendingChange loadPending(UUID id) {
        PendingChange change = changes.findForDecision(id).filter(found -> visible(view(found)))
                .orElseThrow(() -> ApiException.notFound(CHANGE_NOT_FOUND, "Change not found."));
        if (change.getStatus() != PendingChangeStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, CHANGE_NOT_PENDING,
                    "This change has already been " + change.getStatus().name().toLowerCase() + ".");
        }
        return change;
    }

    private PendingChange load(UUID id) {
        return changes.findById(id).filter(change -> visible(view(change)))
                .orElseThrow(() -> ApiException.notFound(CHANGE_NOT_FOUND, "Change not found."));
    }

    private PendingChangeView view(PendingChange change) {
        return new PendingChangeView(change.getId(), change.getSubjectType(), change.getOperation(),
                change.getSubjectId(), change.getBaseVersion(), change.getSummary(),
                jsonMapper.readTree(change.getPayload()), change.getStatus(), change.getProposedBy(),
                change.getProposedAt(), change.getDecidedBy(), change.getDecidedAt(), change.getDecisionComment());
    }

    private void audit(String action, PendingChange change, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subjectType", change.getSubjectType());
        details.put("operation", change.getOperation().name());
        details.put("subjectId", change.getSubjectId().toString());
        details.put("summary", change.getSummary());
        details.put("proposedBy", change.getProposedBy().toString());
        details.putAll(extra);
        auditTrail.record(AuditEntry.success(action, RESOURCE, change.getId(), details));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
