package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.workflow.api.ApprovalOutcome;
import com.jmr.jdms.workflow.api.ApprovalSubject;
import com.jmr.jdms.workflow.api.ApprovalSubjectHandler;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pending changes as approval subjects. {@link PendingChangeService} applies or rejects the change before it
 * completes the task, so the process outcome itself changes nothing here.
 */
@Component
class PendingChangeSubjects implements ApprovalSubjectHandler {

    static final String SUBJECT = "PENDING_CHANGE";

    private final PendingChangeService changes;

    PendingChangeSubjects(PendingChangeService changes) {
        this.changes = changes;
    }

    @Override
    public Set<String> subjectTypes() {
        return Set.of(SUBJECT);
    }

    @Override
    public boolean canView(ApprovalSubject subject) {
        return changes.isVisible(subject.subjectId());
    }

    @Override
    public boolean canDecide(ApprovalSubject subject) {
        return changes.canDecide(subject.subjectId());
    }

    @Override
    public Map<String, Object> routingFacts(ApprovalSubject subject) {
        return Map.of();
    }

    @Override
    public void onOutcome(ApprovalSubject subject, ApprovalOutcome outcome) {
        // decided and applied by PendingChangeService
    }
}
