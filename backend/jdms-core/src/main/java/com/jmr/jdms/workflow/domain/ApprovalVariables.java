package com.jmr.jdms.workflow.domain;

/** Names of the process and task variables the approval processes use (see the BPMN files). */
final class ApprovalVariables {

    static final String SUBJECT_TYPE = "subjectType";
    static final String HISTORY_TYPE = "historyType";
    static final String SUBJECT_ID = "subjectId";
    static final String DOCUMENT_ID = "documentId";
    static final String VERSION_ID = "versionId";
    static final String INITIATOR = "initiator";
    static final String SUMMARY = "summary";
    static final String ROUND = "round";
    static final String DECISION = "decision";
    static final String SLA_HOURS = "slaHours";

    /** single-approval: comma-separated candidate roles. */
    static final String APPROVER_ROLES = "approverRoles";
    /** multi-stage-approval: the stage plan (JSON) and the current stage's key (multi-instance element). */
    static final String STAGE_PLAN = "stagePlan";
    static final String STAGE = "stage";
    /** committee-approval. */
    static final String COMMITTEE_ID = "committeeId";
    static final String COMMITTEE_CODE = "committeeCode";
    static final String COMMITTEE_NAME = "committeeName";
    static final String COMMITTEE_MEMBERS = "committeeMembers";
    static final String QUORUM = "quorum";

    /** Task-local: the stage the task decides. */
    static final String STAGE_KEY = "stageKey";
    static final String STAGE_NAME = "stageName";

    /** Task definition keys. */
    static final String TASK_APPROVE = "approve";
    static final String TASK_STAGE = "stage";
    static final String TASK_VOTE = "vote";
    static final String TASK_AMEND = "amend";

    private ApprovalVariables() {
    }
}
