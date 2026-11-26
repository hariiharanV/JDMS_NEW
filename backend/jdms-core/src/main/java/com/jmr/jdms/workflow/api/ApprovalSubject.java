package com.jmr.jdms.workflow.api;

import java.util.UUID;

/** The item an approval process decides, as passed to its {@link ApprovalSubjectHandler}. */
public record ApprovalSubject(String subjectType, UUID subjectId, UUID documentId, UUID versionId, UUID initiatorId,
        String processInstanceId) {
}
