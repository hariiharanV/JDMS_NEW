package com.jmr.jdms.metadata.web;

import com.jmr.jdms.metadata.domain.LanguageAdministration;
import com.jmr.jdms.metadata.domain.LanguageAdministration.LanguagesPayload;
import com.jmr.jdms.metadata.domain.LanguageAdministration.LanguagesView;
import com.jmr.jdms.shared.web.ETags;
import com.jmr.jdms.workflow.api.PendingChangeView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /admin/languages}: the languages the tenant's screens offer (ADR-0023). A change is a proposal answered
 * with 202; a second tenant admin approves it under {@code /admin/pending-changes}.
 */
@RestController
@RequestMapping("/api/v1/admin/languages")
@PreAuthorize("@access.can('ADMIN_METADATA')")
class LanguageAdminController {

    private final LanguageAdministration languages;

    LanguageAdminController(LanguageAdministration languages) {
        this.languages = languages;
    }

    @GetMapping
    ResponseEntity<LanguagesView> get() {
        LanguagesView view = languages.get();
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PutMapping
    ResponseEntity<PendingChangeView> update(@RequestBody LanguagesPayload request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(languages.proposeUpdate(ETags.requiredVersion(ifMatch), request));
    }
}
