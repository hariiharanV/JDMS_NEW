package com.jmr.jdms.workflow.domain;

import static com.jmr.jdms.workflow.domain.ApprovalVariables.DECISION;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.DOCUMENT_ID;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.HISTORY_TYPE;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.INITIATOR;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.ROUND;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE_KEY;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE_NAME;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.SUBJECT_ID;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.SUBJECT_TYPE;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.SUMMARY;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.VERSION_ID;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.workflow.api.ApprovalDecision;
import com.jmr.jdms.workflow.api.ApprovalProcesses;
import com.jmr.jdms.workflow.api.ApprovalRecordView;
import com.jmr.jdms.workflow.api.ApprovalRequest;
import com.jmr.jdms.workflow.api.ApprovalSubject;
import com.jmr.jdms.workflow.api.ApprovalSubjectHandler;
import com.jmr.jdms.workflow.api.Approvals;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts approval processes in the caller's transaction, so the subject's state, the Flowable process and the
 * approval history commit together or not at all. Subject handlers are looked up lazily: they depend on
 * {@link Approvals}, which this service implements.
 */
@Service
public class ApprovalEngine implements Approvals {

    static final String RESOURCE_PROCESS = "APPROVAL_PROCESS";

    private final RuntimeService runtime;
    private final TaskService tasks;
    private final HistoryService history;
    private final ApprovalRouting routing;
    private final ApprovalRecordRepository records;
    private final ObjectProvider<ApprovalSubjectHandler> handlerProvider;
    private final AuditTrail auditTrail;
    private volatile Map<String, ApprovalSubjectHandler> handlers;

    ApprovalEngine(RuntimeService runtime, TaskService tasks, HistoryService history, ApprovalRouting routing,
            ApprovalRecordRepository records, ObjectProvider<ApprovalSubjectHandler> handlerProvider,
            AuditTrail auditTrail) {
        this.runtime = runtime;
        this.tasks = tasks;
        this.history = history;
        this.routing = routing;
        this.records = records;
        this.handlerProvider = handlerProvider;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String start(ApprovalRequest request) {
        return start(request, request.subjectType(), Set.of());
    }

    /**
     * @param historyType   the subject type recorded in the approval history
     * @param approverRoles candidate roles of a single approval (empty: checkers)
     */
    String start(ApprovalRequest request, String historyType, Set<String> approverRoles) {
        if (!ApprovalProcesses.KEYS.contains(request.processKey())) {
            throw new IllegalArgumentException("Unknown approval process " + request.processKey());
        }
        RequestContext maker = RequestContext.require();
        ApprovalSubjectHandler handler = handler(request.subjectType());
        ApprovalSubject subject = new ApprovalSubject(request.subjectType(), request.subjectId(),
                request.documentId(), request.versionId(), maker.subjectId(), null);

        Map<String, Object> variables = new HashMap<>();
        variables.put(SUBJECT_TYPE, request.subjectType());
        variables.put(HISTORY_TYPE, historyType);
        variables.put(SUBJECT_ID, request.subjectId().toString());
        putIfPresent(variables, DOCUMENT_ID, request.documentId());
        putIfPresent(variables, VERSION_ID, request.versionId());
        variables.put(INITIATOR, maker.subjectId().toString());
        variables.put(SUMMARY, request.summary());
        variables.put(ROUND, 1);
        variables.put(DECISION, "");
        variables.putAll(routing.route(request.processKey(), handler.routingFacts(subject), maker.subjectId(),
                approverRoles));

        ProcessInstance instance = runtime.createProcessInstanceBuilder().processDefinitionKey(request.processKey())
                .tenantId(maker.tenantId().toString()).fallbackToDefaultTenant()
                // instances and tasks belong to the tenant even when the definition is the shared default
                .overrideProcessDefinitionTenantId(maker.tenantId().toString())
                .businessKey(request.subjectType() + ":" + request.subjectId()).name(abbreviate(request.summary()))
                .variables(variables).start();
        ProcessSubject started = subject(instance.getId(), instance.getProcessDefinitionId(), variables);
        record(started, null, 1, null, null, ApprovalDecision.SUBMIT, null);
        audit("APPROVAL_STARTED", started, Map.of("processKey", request.processKey()));
        return instance.getId();
    }

    /** Process instance recorded on imported legacy history (approval_record.process_instance_id). */
    static final String LEGACY_PROCESS = "legacy-import";

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalRecordView> history(UUID documentId) {
        return views(records.findByDocumentIdOrderByDecidedAtAscIdAsc(documentId));
    }

    @Override
    @Transactional
    public int importHistory(UUID documentId, UUID versionId, List<ImportedDecision> decisions) {
        ProcessSubject legacy = new ProcessSubject(LEGACY_PROCESS, LEGACY_PROCESS, "DOCUMENT", "DOCUMENT", documentId,
                documentId, versionId, null, null, 1);
        for (ImportedDecision decision : decisions) {
            records.save(new ApprovalRecord(legacy, null, Math.max(1, decision.round()), null, decision.stageName(),
                    legacyActor(decision.actorName()), decision.actorName(), decision.decision(), decision.comment(),
                    decision.decidedAt()));
        }
        return decisions.size();
    }

    /** A stable ID per legacy user name, so the same legacy actor groups together in reports. */
    static UUID legacyActor(String actorName) {
        return UUID.nameUUIDFromBytes(("legacy-jdms-user:" + actorName).getBytes(StandardCharsets.UTF_8));
    }

    List<ApprovalRecordView> processHistory(String processInstanceId) {
        return views(records.findByProcessInstanceIdOrderByDecidedAtAscIdAsc(processInstanceId));
    }

    ApprovalRecord record(ProcessSubject subject, String taskId, int round, String stageKey, String stageName,
            ApprovalDecision decision, String comment) {
        RequestContext actor = RequestContext.require();
        return records.saveAndFlush(new ApprovalRecord(subject, taskId, round, stageKey, stageName,
                actor.subjectId(), actor.username(), decision, comment, now()));
    }

    /** Records a decision on a pending change's task and completes it, ending its single-approval process. */
    void decideChange(String processInstanceId, ApprovalDecision decision, String comment) {
        if (processInstanceId == null) {
            return; // proposed before approval processes existed
        }
        Task task = tasks.createTaskQuery().processInstanceId(processInstanceId).singleResult();
        if (task == null) {
            return;
        }
        ProcessSubject subject = subject(processInstanceId, task.getProcessDefinitionId(),
                runtime.getVariables(processInstanceId));
        Map<String, Object> local = tasks.getVariablesLocal(task.getId());
        record(subject, task.getId(), subject.round(), (String) local.get(STAGE_KEY), (String) local.get(STAGE_NAME),
                decision, comment);
        tasks.complete(task.getId(), Map.of(DECISION, decision.name()));
        audit("APPROVAL_DECIDED", subject, Map.of("decision", decision.name()));
    }

    /** Ends a pending change's process without a decision (cancelled by the proposer, or stale). */
    void abortChange(String processInstanceId, String reason) {
        if (processInstanceId != null && runtime.createProcessInstanceQuery().processInstanceId(processInstanceId)
                .count() > 0) {
            runtime.deleteProcessInstance(processInstanceId, reason);
        }
    }

    ApprovalSubjectHandler handler(String subjectType) {
        ApprovalSubjectHandler handler = handlers().get(subjectType);
        if (handler == null) {
            throw new IllegalStateException("No approval subject handler for " + subjectType);
        }
        return handler;
    }

    String processKey(String processInstanceId) {
        HistoricProcessInstance instance = history.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return instance == null ? null : instance.getProcessDefinitionKey();
    }

    static ProcessSubject subject(String processInstanceId, String processDefinitionId,
            Map<String, Object> variables) {
        return ProcessSubject.of(processInstanceId, processDefinitionId, variables);
    }

    void audit(String action, ProcessSubject subject, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subjectType", subject.historyType());
        details.put("subjectId", subject.subjectId().toString());
        if (subject.documentId() != null) {
            details.put("documentId", subject.documentId().toString());
        }
        details.put("round", subject.round());
        details.putAll(extra);
        auditTrail.record(AuditEntry.success(action, RESOURCE_PROCESS, subject.processInstanceId(), details));
    }

    private Map<String, ApprovalSubjectHandler> handlers() {
        Map<String, ApprovalSubjectHandler> current = handlers;
        if (current == null) {
            Map<String, ApprovalSubjectHandler> map = new HashMap<>();
            handlerProvider.orderedStream().forEach(handler -> handler.subjectTypes()
                    .forEach(type -> map.put(type, handler)));
            current = Map.copyOf(map);
            handlers = current;
        }
        return current;
    }

    private List<ApprovalRecordView> views(List<ApprovalRecord> entries) {
        Map<String, String> keys = new HashMap<>();
        Function<String, String> processKey = id -> keys.computeIfAbsent(id, this::processKey);
        return entries.stream().map(entry -> new ApprovalRecordView(entry.getId(), entry.getSubjectType(),
                entry.getSubjectId(), entry.getVersionId(), processKey.apply(entry.getProcessInstanceId()),
                entry.getRound(), entry.getStageKey(), entry.getStageName(), entry.getActorId(),
                entry.getActorName(), entry.getDecision(), entry.getComment(), entry.getDecidedAt())).toList();
    }

    private static void putIfPresent(Map<String, Object> variables, String name, UUID value) {
        if (value != null) {
            variables.put(name, value.toString());
        }
    }

    private static String abbreviate(String text) {
        return text == null || text.length() <= 250 ? text : text.substring(0, 249) + "…";
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
