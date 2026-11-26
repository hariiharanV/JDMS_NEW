package com.jmr.jdms.workflow.domain;

import static com.jmr.jdms.workflow.domain.ApprovalVariables.DOCUMENT_ID;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.HISTORY_TYPE;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.INITIATOR;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.ROUND;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.SUBJECT_ID;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.SUBJECT_TYPE;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.SUMMARY;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.VERSION_ID;

import com.jmr.jdms.workflow.api.ApprovalSubject;
import java.util.Map;
import java.util.UUID;

/** An approval process's subject and state, read from its variables. */
record ProcessSubject(String processInstanceId, String processKey, String subjectType, String historyType,
        UUID subjectId, UUID documentId, UUID versionId, UUID initiatorId, String summary, int round) {

    static ProcessSubject of(String processInstanceId, String processDefinitionId, Map<String, Object> variables) {
        return new ProcessSubject(processInstanceId, processKeyOf(processDefinitionId),
                (String) variables.get(SUBJECT_TYPE), (String) variables.get(HISTORY_TYPE),
                uuid(variables.get(SUBJECT_ID)), uuid(variables.get(DOCUMENT_ID)), uuid(variables.get(VERSION_ID)),
                uuid(variables.get(INITIATOR)), (String) variables.get(SUMMARY),
                variables.get(ROUND) instanceof Number number ? number.intValue() : 1);
    }

    ApprovalSubject api() {
        return new ApprovalSubject(subjectType, subjectId, documentId, versionId, initiatorId, processInstanceId);
    }

    /** Process definition IDs look like {@code single-approval:3:<uuid>}. */
    static String processKeyOf(String processDefinitionId) {
        return processDefinitionId == null ? null : processDefinitionId.split(":", 2)[0];
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }
}
