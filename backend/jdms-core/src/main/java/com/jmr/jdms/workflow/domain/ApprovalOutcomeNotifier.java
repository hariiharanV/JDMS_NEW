package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.workflow.api.ApprovalOutcome;
import java.util.Map;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/**
 * Reports a process outcome to the subject's owner: {@code ${approvalOutcome.notify(execution, 'APPROVED')}}. Runs
 * inside the transaction that completed the deciding task.
 */
@Component("approvalOutcome")
class ApprovalOutcomeNotifier {

    private final ApprovalEngine engine;

    ApprovalOutcomeNotifier(ApprovalEngine engine) {
        this.engine = engine;
    }

    public void notify(DelegateExecution execution, String outcome) {
        ProcessSubject subject = ApprovalEngine.subject(execution.getProcessInstanceId(),
                execution.getProcessDefinitionId(), execution.getVariables());
        engine.handler(subject.subjectType()).onOutcome(subject.api(), ApprovalOutcome.valueOf(outcome));
        engine.audit("APPROVAL_" + outcome, subject, Map.of());
    }
}
