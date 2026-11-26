package com.jmr.jdms.workflow.api;

import java.util.UUID;

/**
 * Starts an approval for a subject: an uploaded document, a new version or (internally) a pending change.
 *
 * @param processKey one of {@link ApprovalProcesses#KEYS}
 * @param subjectType the {@link ApprovalSubjectHandler} that owns the subject (e.g. {@code DOCUMENT})
 * @param documentId the document concerned, if any (approval history is shown on it)
 * @param versionId the version concerned, if any
 * @param summary one line describing the item in the inbox
 */
public record ApprovalRequest(String processKey, String subjectType, UUID subjectId, UUID documentId,
        UUID versionId, String summary) {
}
