package com.jmr.jdms.workflow.domain;

import static com.jmr.jdms.workflow.domain.ApprovalVariables.APPROVER_ROLES;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.COMMITTEE_CODE;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.COMMITTEE_NAME;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.SLA_HOURS;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE_KEY;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE_NAME;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE_PLAN;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.SUMMARY;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.TASK_AMEND;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.TASK_APPROVE;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.TASK_STAGE;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.TASK_VOTE;

import java.time.Duration;
import java.util.Arrays;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;

/**
 * Configures each approval task when it is created: name, the stage it decides (task-local variables), candidate
 * roles and due date (SLA). Committee votes are assigned to their member and amend tasks to the maker by the BPMN.
 */
@Component("approvalTaskSetup")
class ApprovalTaskSetup implements TaskListener {

    static final String AMEND_STAGE = "AMEND";
    static final String SINGLE_STAGE = "APPROVAL";

    private final ApprovalRouting routing;

    ApprovalTaskSetup(ApprovalRouting routing) {
        this.routing = routing;
    }

    @Override
    public void notify(DelegateTask task) {
        task.setDescription((String) task.getVariable(SUMMARY));
        switch (task.getTaskDefinitionKey()) {
            case TASK_APPROVE -> {
                configure(task, SINGLE_STAGE, "Approval", number(task.getVariable(SLA_HOURS)));
                Arrays.stream(((String) task.getVariable(APPROVER_ROLES)).split(","))
                        .forEach(task::addCandidateGroup);
            }
            case TASK_STAGE -> {
                StagePlan.Stage stage = routing.plan((String) task.getVariable(STAGE_PLAN))
                        .stage((String) task.getVariable(STAGE));
                configure(task, stage.key(), stage.name(), stage.slaHours());
                stage.candidateRoles().forEach(task::addCandidateGroup);
            }
            case TASK_VOTE -> configure(task, (String) task.getVariable(COMMITTEE_CODE),
                    "Committee vote: " + task.getVariable(COMMITTEE_NAME), number(task.getVariable(SLA_HOURS)));
            case TASK_AMEND -> configure(task, AMEND_STAGE, "Amend and resubmit", 0);
            default -> throw new IllegalStateException("Unexpected approval task " + task.getTaskDefinitionKey());
        }
    }

    private static void configure(DelegateTask task, String stageKey, String stageName, int slaHours) {
        task.setName(stageName);
        task.setVariableLocal(STAGE_KEY, stageKey);
        task.setVariableLocal(STAGE_NAME, stageName);
        if (slaHours > 0) {
            task.setDueDate(FlowableDates.date(FlowableDates.instant(task.getCreateTime())
                    .plus(Duration.ofHours(slaHours))));
        }
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
