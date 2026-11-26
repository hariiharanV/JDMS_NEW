package com.jmr.jdms.workflow.domain;

import static com.jmr.jdms.workflow.domain.ApprovalVariables.COMMITTEE_MEMBERS;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.QUORUM;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE_PLAN;

import com.jmr.jdms.workflow.api.ApprovalDecision;
import java.util.Arrays;
import java.util.List;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/**
 * Expressions of the approval processes: multi-instance collections ({@code ${approvalPlan.stages(execution)}},
 * committee members) and the committee tally.
 */
@Component("approvalPlan")
class ApprovalPlan {

    private final ApprovalRouting routing;
    private final ApprovalRecordRepository records;

    ApprovalPlan(ApprovalRouting routing, ApprovalRecordRepository records) {
        this.routing = routing;
        this.records = records;
    }

    /** Stage keys of a multi-stage approval, in order (the plan is set when submitted and on resubmission). */
    public List<String> stages(DelegateExecution execution) {
        return routing.plan((String) execution.getVariable(STAGE_PLAN)).keys();
    }

    /** User IDs of the committee members who vote (fixed when submitted). */
    public List<String> members(DelegateExecution execution) {
        return Arrays.stream(((String) execution.getVariable(COMMITTEE_MEMBERS)).split(",")).toList();
    }

    /**
     * The vote is over when the approvals reach the quorum, or when the rejections leave too few possible approvals.
     * Concurrent votes cannot both miss each other: they update the same multi-instance counters, so one of them
     * fails with an optimistic-locking conflict and is retried.
     */
    public boolean voteSettled(DelegateExecution execution) {
        int quorum = ((Number) execution.getVariable(QUORUM)).intValue();
        long approvals = votes(execution, ApprovalDecision.VOTE_APPROVE);
        long rejections = votes(execution, ApprovalDecision.VOTE_REJECT);
        return approvals >= quorum || rejections > members(execution).size() - quorum;
    }

    public boolean voteApproved(DelegateExecution execution) {
        return votes(execution, ApprovalDecision.VOTE_APPROVE) >= ((Number) execution.getVariable(QUORUM)).intValue();
    }

    private long votes(DelegateExecution execution, ApprovalDecision decision) {
        return records.countByProcessInstanceIdAndDecision(execution.getProcessInstanceId(), decision);
    }
}
