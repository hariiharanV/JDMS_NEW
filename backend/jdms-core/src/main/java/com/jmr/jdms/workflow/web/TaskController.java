package com.jmr.jdms.workflow.web;

import com.jmr.jdms.shared.error.ErrorCodes;
import com.jmr.jdms.shared.error.Problems;
import com.jmr.jdms.shared.web.PageResponse;
import com.jmr.jdms.workflow.domain.ApprovalTaskService;
import com.jmr.jdms.workflow.domain.ApprovalTaskService.Completion;
import com.jmr.jdms.workflow.domain.ApprovalTaskService.TaskDecision;
import com.jmr.jdms.workflow.domain.ApprovalTaskService.TaskDetail;
import com.jmr.jdms.workflow.domain.ApprovalTaskService.TaskView;
import com.jmr.jdms.workflow.domain.PendingChangeService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Approval task inbox ({@code /tasks} in {@code docs/04-api-outline.md}). Open to every authenticated user: each
 * sees the tasks they can act on now, and every decision is authorised per task in {@link ApprovalTaskService}.
 */
@RestController
@RequestMapping("/api/v1/tasks")
@PreAuthorize("isAuthenticated()")
class TaskController {

    private final ApprovalTaskService tasks;

    TaskController(ApprovalTaskService tasks) {
        this.tasks = tasks;
    }

    /** {@code assignedTo=me} (the default and only value): assigned to the caller or offered to their roles. */
    @GetMapping
    PageResponse<TaskView> inbox(@RequestParam(defaultValue = "me") @Pattern(regexp = "me") String assignedTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(tasks.inbox(PageRequest.of(page, size)), view -> view);
    }

    @GetMapping("/{id}")
    TaskDetail get(@PathVariable String id) {
        return tasks.get(id);
    }

    @PostMapping("/{id}/claim")
    TaskView claim(@PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return tasks.claim(id);
    }

    /** 200 when decided; for a pending change that could not be applied, 409 {@code CHANGE_STALE}. */
    @PostMapping("/{id}/complete")
    ResponseEntity<?> complete(@PathVariable String id, @RequestBody CompleteRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (request.decision() == null) {
            return ResponseEntity.unprocessableContent().body(Problems.of(HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCodes.VALIDATION_FAILED, "A decision is required."));
        }
        Completion completion = tasks.complete(id, request.decision(), request.comment());
        if (completion.staleReason() != null) {
            ProblemDetail problem = Problems.of(HttpStatus.CONFLICT, PendingChangeService.CHANGE_STALE,
                    completion.staleReason());
            problem.setProperty("pendingChangeId", completion.pendingChangeId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
        return ResponseEntity.ok(new CompleteResponse(id, request.decision(), completion.pendingChangeId()));
    }

    record CompleteRequest(TaskDecision decision, @Size(max = 1000) String comment) {
    }

    record CompleteResponse(String taskId, TaskDecision decision, UUID pendingChangeId) {
    }
}
