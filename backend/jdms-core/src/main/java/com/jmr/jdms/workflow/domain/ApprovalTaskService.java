package com.jmr.jdms.workflow.domain;

import static com.jmr.jdms.workflow.domain.ApprovalVariables.DECISION;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.ROUND;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE_KEY;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE_NAME;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE_PLAN;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.TASK_AMEND;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.TASK_VOTE;

import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ErrorCodes;
import com.jmr.jdms.workflow.api.ApprovalDecision;
import com.jmr.jdms.workflow.api.ApprovalProcesses;
import com.jmr.jdms.workflow.api.ApprovalRecordView;
import com.jmr.jdms.workflow.api.ApprovalSubjectHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The task inbox and task decisions (spec §4.4, {@code /tasks}). Every rule is enforced here, not only in the UI:
 * the maker can never decide their own item at any stage; a user decides at most one task of an item per round (no
 * double vote, distinct approvers for successive stages); deciding needs the subject owner's policy check; reject
 * and return need a comment. A decision, its approval record and the process step commit together.
 */
@Service
public class ApprovalTaskService {

    public static final String TASK_NOT_FOUND = "TASK_NOT_FOUND";
    public static final String TASK_MAKER_EXCLUDED = "TASK_MAKER_EXCLUDED";
    public static final String TASK_ALREADY_CLAIMED = "TASK_ALREADY_CLAIMED";
    public static final String TASK_ALREADY_DECIDED = "TASK_ALREADY_DECIDED";
    public static final String TASK_CONFLICT = "TASK_CONFLICT";
    public static final String DECISION_NOT_ALLOWED = "DECISION_NOT_ALLOWED";
    public static final String COMMENT_REQUIRED = "COMMENT_REQUIRED";

    /** Candidate tasks examined per inbox request before the per-item policy filter (PoC volumes). */
    static final int INBOX_SCAN_LIMIT = 1000;
    private static final int CONFLICT_RETRIES = 3;
    static final int MAX_COMMENT = 1000;

    public enum TaskKind {
        APPROVAL, VOTE, AMEND
    }

    public enum TaskDecision {
        APPROVE, REJECT, RETURN, RESUBMIT
    }

    /** {@code DUE_SOON}: less than a quarter of the stage's SLA is left. */
    public enum SlaStatus {
        NONE, ON_TIME, DUE_SOON, OVERDUE
    }

    public record TaskView(String id, String name, TaskKind kind, String processKey, String stageKey,
            String stageName, String subjectType, UUID subjectId, UUID documentId, UUID versionId, String summary,
            int round, UUID makerId, String assignee, Instant createdAt, Instant dueAt, SlaStatus slaStatus,
            List<TaskDecision> decisions) {
    }

    public record TaskDetail(TaskView task, List<ApprovalRecordView> history) {
    }

    /** The outcome of a decision; {@code staleReason} is set when an approved pending change went stale. */
    public record Completion(String staleReason, UUID pendingChangeId) {
    }

    private record LoadedTask(Task task, ProcessSubject subject, TaskKind kind) {

        String stageKey() {
            return (String) task.getTaskLocalVariables().get(STAGE_KEY);
        }

        String stageName() {
            return (String) task.getTaskLocalVariables().get(STAGE_NAME);
        }

        boolean assignedTo(UUID user) {
            return user.toString().equals(task.getAssignee());
        }
    }

    private final TaskService tasks;
    private final ApprovalEngine engine;
    private final ApprovalRouting routing;
    private final ApprovalRecordRepository records;
    private final PendingChangeService pendingChanges;
    private final TransactionTemplate transactions;

    ApprovalTaskService(TaskService tasks, ApprovalEngine engine, ApprovalRouting routing,
            ApprovalRecordRepository records, PendingChangeService pendingChanges, TransactionTemplate transactions) {
        this.tasks = tasks;
        this.engine = engine;
        this.routing = routing;
        this.records = records;
        this.pendingChanges = pendingChanges;
        this.transactions = transactions;
    }

    /** Tasks the caller can act on now: assigned to them, or offered to one of their roles, oldest first. */
    public Page<TaskView> inbox(Pageable pageable) {
        return transactions.execute(status -> {
            RequestContext caller = RequestContext.require();
            var query = tasks.createTaskQuery().taskTenantId(caller.tenantId().toString())
                    .processDefinitionKeyIn(ApprovalProcesses.KEYS).or().taskAssignee(caller.subjectId().toString())
                    .taskCandidateUser(caller.subjectId().toString());
            if (!caller.roles().isEmpty()) {
                query = query.taskCandidateGroupIn(caller.roles());
            }
            List<TaskView> actionable = query.endOr().includeProcessVariables().includeTaskLocalVariables()
                    .orderByTaskCreateTime().asc().listPage(0, INBOX_SCAN_LIMIT).stream()
                    .map(this::loaded).filter(task -> actionable(task, caller, true)).map(this::view).toList();
            int from = (int) Math.min(pageable.getOffset(), actionable.size());
            int to = Math.min(from + pageable.getPageSize(), actionable.size());
            return new PageImpl<>(actionable.subList(from, to), pageable, actionable.size());
        });
    }

    /** A task with its item's history: for those who can act on it and for the maker. */
    public TaskDetail get(String taskId) {
        return transactions.execute(status -> {
            RequestContext caller = RequestContext.require();
            LoadedTask task = load(taskId);
            boolean maker = caller.subjectId().equals(task.subject().initiatorId());
            if (!maker && !visibleToApprover(task, caller)) {
                throw notFound();
            }
            return new TaskDetail(view(task), engine.processHistory(task.task().getProcessInstanceId()));
        });
    }

    public TaskView claim(String taskId) {
        return withConflictRetry(() -> transactions.execute(status -> {
            RequestContext caller = RequestContext.require();
            LoadedTask task = load(taskId);
            requireMayDecide(task, caller);
            if (task.task().getAssignee() == null) {
                try {
                    tasks.claim(taskId, caller.subjectId().toString());
                } catch (FlowableTaskAlreadyClaimedException e) {
                    throw claimed();
                }
                engine.audit("TASK_CLAIMED", task.subject(), Map.of("taskId", taskId));
            }
            return view(load(taskId));
        }));
    }

    public Completion complete(String taskId, TaskDecision decision, String comment) {
        String trimmed = comment == null || comment.isBlank() ? null : comment.trim();
        if (trimmed != null && trimmed.length() > MAX_COMMENT) {
            throw ApiException.unprocessable(ErrorCodes.VALIDATION_FAILED,
                    "The comment must be at most " + MAX_COMMENT + " characters.");
        }
        return withConflictRetry(() -> transactions.execute(status -> {
            RequestContext caller = RequestContext.require();
            LoadedTask task = load(taskId);
            if (task.kind() == TaskKind.AMEND) {
                return resubmit(task, caller, decision, trimmed);
            }
            requireMayDecide(task, caller);
            if (!decisions(task).contains(decision)) {
                throw ApiException.unprocessable(DECISION_NOT_ALLOWED, "This task cannot be decided with "
                        + decision + ".");
            }
            if ((decision == TaskDecision.REJECT || decision == TaskDecision.RETURN) && trimmed == null) {
                throw ApiException.unprocessable(COMMENT_REQUIRED, "A comment is required to reject or return.");
            }
            if (PendingChangeSubjects.SUBJECT.equals(task.subject().subjectType())) {
                return decideChange(task, decision, trimmed);
            }
            ApprovalDecision recorded = task.kind() == TaskKind.VOTE
                    ? (decision == TaskDecision.APPROVE ? ApprovalDecision.VOTE_APPROVE : ApprovalDecision.VOTE_REJECT)
                    : ApprovalDecision.valueOf(decision.name());
            engine.record(task.subject(), taskId, task.subject().round(), task.stageKey(), task.stageName(), recorded,
                    trimmed);
            tasks.complete(taskId, Map.of(DECISION, decision.name()));
            engine.audit("APPROVAL_DECIDED", task.subject(), Map.of("taskId", taskId, "decision", recorded.name(),
                    "stageKey", String.valueOf(task.stageKey())));
            return new Completion(null, null);
        }));
    }

    /** The maker resubmits after a return: a new round, routed again (amounts may have changed). */
    private Completion resubmit(LoadedTask task, RequestContext caller, TaskDecision decision, String comment) {
        if (!task.assignedTo(caller.subjectId())) {
            throw notFound();
        }
        if (decision != TaskDecision.RESUBMIT) {
            throw ApiException.unprocessable(DECISION_NOT_ALLOWED, "A returned item can only be resubmitted.");
        }
        ProcessSubject subject = task.subject();
        int round = subject.round() + 1;
        Map<String, Object> variables = new HashMap<>();
        variables.put(DECISION, decision.name());
        variables.put(ROUND, round);
        if (ApprovalProcesses.MULTI_STAGE.equals(subject.processKey())) {
            ApprovalSubjectHandler handler = engine.handler(subject.subjectType());
            variables.put(STAGE_PLAN, jsonOf(routing.stages(handler.routingFacts(subject.api()))));
        }
        engine.record(subject, task.task().getId(), round, task.stageKey(), task.stageName(), ApprovalDecision.SUBMIT,
                comment);
        tasks.complete(task.task().getId(), variables);
        engine.audit("APPROVAL_RESUBMITTED", subject, Map.of("taskId", task.task().getId(), "round", round));
        return new Completion(null, null);
    }

    private Completion decideChange(LoadedTask task, TaskDecision decision, String comment) {
        UUID changeId = task.subject().subjectId();
        if (decision == TaskDecision.APPROVE) {
            return new Completion(pendingChanges.approve(changeId, comment).staleReason(), changeId);
        }
        pendingChanges.reject(changeId, comment);
        return new Completion(null, changeId);
    }

    /** Checks in order, so each refusal says why: maker, visibility, claim, round, policy. */
    private void requireMayDecide(LoadedTask task, RequestContext caller) {
        if (task.kind() == TaskKind.AMEND) {
            if (!task.assignedTo(caller.subjectId())) {
                throw notFound();
            }
            return;
        }
        if (caller.subjectId().equals(task.subject().initiatorId())) {
            throw ApiException.forbidden(TASK_MAKER_EXCLUDED, "You cannot decide an item you submitted.");
        }
        if (!visibleToApprover(task, caller)) {
            throw notFound();
        }
        if (task.task().getAssignee() != null && !task.assignedTo(caller.subjectId())) {
            throw claimed();
        }
        if (decidedThisRound(task, caller)) {
            throw new ApiException(HttpStatus.CONFLICT, TASK_ALREADY_DECIDED,
                    "You have already decided this item in this round.");
        }
        if (!engine.handler(task.subject().subjectType()).canDecide(task.subject().api())) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED, "You are not allowed to decide this item.");
        }
    }

    /** {@code candidateChecked}: the task came from a candidate query, so its identity links need no re-check. */
    private boolean actionable(LoadedTask task, RequestContext caller, boolean candidateChecked) {
        if (task.kind() == TaskKind.AMEND) {
            return task.assignedTo(caller.subjectId());
        }
        return !caller.subjectId().equals(task.subject().initiatorId())
                && (candidateChecked || isCandidate(task, caller))
                && (task.task().getAssignee() == null || task.assignedTo(caller.subjectId()))
                && !decidedThisRound(task, caller)
                && engine.handler(task.subject().subjectType()).canDecide(task.subject().api());
    }

    private boolean visibleToApprover(LoadedTask task, RequestContext caller) {
        if (task.kind() == TaskKind.AMEND) {
            return task.assignedTo(caller.subjectId());
        }
        return isCandidate(task, caller) && engine.handler(task.subject().subjectType()).canView(task.subject().api());
    }

    /** Assigned to the caller, or offered to the caller or one of their roles (which includes claimed tasks). */
    private boolean isCandidate(LoadedTask task, RequestContext caller) {
        if (task.task().getAssignee() != null) {
            if (task.assignedTo(caller.subjectId())) {
                return true;
            }
        }
        String user = caller.subjectId().toString();
        for (IdentityLink link : tasks.getIdentityLinksForTask(task.task().getId())) {
            if (IdentityLinkType.CANDIDATE.equals(link.getType()) && (user.equals(link.getUserId())
                    || (link.getGroupId() != null && caller.roles().contains(link.getGroupId())))) {
                return true;
            }
        }
        return false;
    }

    private boolean decidedThisRound(LoadedTask task, RequestContext caller) {
        return records.existsByProcessInstanceIdAndRoundAndActorIdAndTaskIdIsNotNull(
                task.task().getProcessInstanceId(), task.subject().round(), caller.subjectId());
    }

    private List<TaskDecision> decisions(LoadedTask task) {
        if (task.kind() == TaskKind.AMEND) {
            return List.of(TaskDecision.RESUBMIT);
        }
        if (task.kind() == TaskKind.VOTE || PendingChangeSubjects.SUBJECT.equals(task.subject().subjectType())) {
            return List.of(TaskDecision.APPROVE, TaskDecision.REJECT);
        }
        return List.of(TaskDecision.APPROVE, TaskDecision.REJECT, TaskDecision.RETURN);
    }

    private LoadedTask load(String taskId) {
        Task task;
        try {
            UUID.fromString(taskId);
            task = tasks.createTaskQuery().taskId(taskId).taskTenantId(RequestContext.require().tenantId().toString())
                    .processDefinitionKeyIn(ApprovalProcesses.KEYS).includeProcessVariables()
                    .includeTaskLocalVariables().singleResult();
        } catch (IllegalArgumentException e) {
            task = null;
        }
        if (task == null) {
            throw notFound();
        }
        return loaded(task);
    }

    private LoadedTask loaded(Task task) {
        ProcessSubject subject = ApprovalEngine.subject(task.getProcessInstanceId(), task.getProcessDefinitionId(),
                task.getProcessVariables());
        TaskKind kind = switch (task.getTaskDefinitionKey()) {
            case TASK_AMEND -> TaskKind.AMEND;
            case TASK_VOTE -> TaskKind.VOTE;
            default -> TaskKind.APPROVAL;
        };
        return new LoadedTask(task, subject, kind);
    }

    private TaskView view(LoadedTask loaded) {
        Task task = loaded.task();
        ProcessSubject subject = loaded.subject();
        Instant created = FlowableDates.instant(task.getCreateTime());
        Instant due = FlowableDates.instant(task.getDueDate());
        return new TaskView(task.getId(), task.getName(), loaded.kind(), subject.processKey(), loaded.stageKey(),
                loaded.stageName(), subject.historyType(), subject.subjectId(), subject.documentId(),
                subject.versionId(), subject.summary(), subject.round(), subject.initiatorId(), task.getAssignee(),
                created, due, sla(created, due), decisions(loaded));
    }

    static SlaStatus sla(Instant created, Instant due) {
        if (due == null) {
            return SlaStatus.NONE;
        }
        Instant now = Instant.now();
        if (now.isAfter(due)) {
            return SlaStatus.OVERDUE;
        }
        Duration window = Duration.between(created, due);
        return Duration.between(now, due).compareTo(window.dividedBy(4)) < 0 ? SlaStatus.DUE_SOON : SlaStatus.ON_TIME;
    }

    private String jsonOf(StagePlan plan) {
        return routing.json(plan);
    }

    /** Concurrent decisions on one item conflict in Flowable; the losing request is retried from scratch. */
    private static <T> T withConflictRetry(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (FlowableObjectNotFoundException e) {
                throw notFound();
            } catch (RuntimeException e) {
                // Flowable's own conflicts, and a subject's optimistic-lock failure raised inside a listener
                if (!isConflict(e)) {
                    throw e;
                }
                if (attempt == CONFLICT_RETRIES) {
                    throw new ApiException(HttpStatus.CONFLICT, TASK_CONFLICT,
                            "The item was changed by another decision; reload and try again.");
                }
            }
        }
    }

    private static boolean isConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof FlowableOptimisticLockingException
                    || cause instanceof OptimisticLockingFailureException) {
                return true;
            }
        }
        return false;
    }

    private static ApiException notFound() {
        return ApiException.notFound(TASK_NOT_FOUND, "Task not found.");
    }

    private static ApiException claimed() {
        return new ApiException(HttpStatus.CONFLICT, TASK_ALREADY_CLAIMED, "Another user has claimed this task.");
    }
}
