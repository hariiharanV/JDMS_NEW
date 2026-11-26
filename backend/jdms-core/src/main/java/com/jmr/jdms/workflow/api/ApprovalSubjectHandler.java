package com.jmr.jdms.workflow.api;

import java.util.Map;
import java.util.Set;

/**
 * Implemented by the module that owns an approval subject (documents, versions...), so the workflow module never
 * depends on it. Called in the transaction of the request that starts or decides the approval.
 */
public interface ApprovalSubjectHandler {

    /** The {@link ApprovalRequest#subjectType()} values this handler owns. */
    Set<String> subjectTypes();

    /** Whether the caller may see the item and its history (otherwise its tasks are reported as not found). */
    boolean canView(ApprovalSubject subject);

    /** Whether the caller may decide the item (through the access policy), apart from separation of duties. */
    boolean canDecide(ApprovalSubject subject);

    /**
     * Facts for the routing tables ({@code approval-stages}, {@code approval-committee}): {@code documentTypeCode},
     * {@code documentCategory}, {@code branchCode}, {@code classification}, {@code amount}.
     */
    Map<String, Object> routingFacts(ApprovalSubject subject);

    /** Applies the outcome to the subject (status changes) and audits it. */
    void onOutcome(ApprovalSubject subject, ApprovalOutcome outcome);
}
