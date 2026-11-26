package com.jmr.jdms.identity.web;

import com.jmr.jdms.identity.domain.CurrentUserQuery;
import com.jmr.jdms.identity.domain.CurrentUserView;
import com.jmr.jdms.identity.domain.NotificationService.NotificationView;
import com.jmr.jdms.identity.domain.NotificationService;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The authenticated caller's own identity and access; any authenticated user or client may call it. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final CurrentUserQuery currentUser;
    private final NotificationService notifications;

    MeController(CurrentUserQuery currentUser, NotificationService notifications) {
        this.currentUser = currentUser;
        this.notifications = notifications;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    CurrentUserView me() {
        return currentUser.describe(RequestContext.require());
    }

    /** The caller's own notifications, newest first. */
    @GetMapping("/notifications")
    @PreAuthorize("isAuthenticated()")
    PageResponse<NotificationView> notifications(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(notifications.mine(PageRequest.of(page, size)), view -> view);
    }

    @PostMapping("/notifications/{id}/read")
    @PreAuthorize("isAuthenticated()")
    NotificationView markRead(@PathVariable UUID id) {
        return notifications.markRead(id);
    }
}
