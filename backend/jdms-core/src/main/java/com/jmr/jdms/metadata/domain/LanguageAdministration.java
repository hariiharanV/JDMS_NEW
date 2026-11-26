package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.shared.security.Roles;
import com.jmr.jdms.tenant.api.TenantLanguages;
import com.jmr.jdms.tenant.api.TenantLanguages.LanguageSettings;
import com.jmr.jdms.workflow.api.ApplyResult;
import com.jmr.jdms.workflow.api.ChangeHandler;
import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.ChangeProposal;
import com.jmr.jdms.workflow.api.ChangeRequests;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The languages the tenant's screens offer (ADR-0023): English always, Arabic when switched on. Tenant admins
 * propose a change; a second one approves it. Arabic names of document types, fields and list values stay optional
 * either way.
 */
@Service
public class LanguageAdministration implements ChangeHandler {

    public static final String SUBJECT = "TENANT_LANGUAGES";

    private final TenantLanguages tenantLanguages;
    private final ChangeRequests changeRequests;
    private final MetadataChangeSupport support;

    LanguageAdministration(TenantLanguages tenantLanguages, ChangeRequests changeRequests,
            MetadataChangeSupport support) {
        this.tenantLanguages = tenantLanguages;
        this.changeRequests = changeRequests;
        this.support = support;
    }

    /** Proposed languages, e.g. {@code ["en", "ar"]}. */
    public record LanguagesPayload(List<String> languages) {
    }

    /** The tenant's languages, every language it could offer, and its default language. */
    public record LanguagesView(List<String> languages, List<String> supported, String defaultLanguage,
            long version) {
    }

    @Transactional(readOnly = true)
    public LanguagesView get() {
        LanguageSettings settings = tenantLanguages.get(tenantId());
        return new LanguagesView(settings.languages(), TenantLanguages.SUPPORTED, settings.defaultLocale(),
                settings.version());
    }

    @Transactional
    public PendingChangeView proposeUpdate(long expectedVersion, LanguagesPayload payload) {
        UUID tenantId = tenantId();
        LanguageSettings settings = tenantLanguages.get(tenantId);
        if (settings.version() != expectedVersion) {
            throw ApiException.staleVersion();
        }
        List<String> languages = normalise(payload, settings.defaultLocale());
        String summary = languages.contains(TenantLanguages.ARABIC) ? "Offer Arabic in the screens"
                : "Offer English only in the screens";
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE, tenantId, null,
                expectedVersion, new LanguagesPayload(languages), summary));
    }

    /** English first, then the others in {@link TenantLanguages#SUPPORTED} order; refuses unknown codes. */
    private static List<String> normalise(LanguagesPayload payload, String defaultLanguage) {
        List<String> proposed = payload == null || payload.languages() == null ? List.of() : payload.languages();
        List<Violation> violations = new ArrayList<>();
        proposed.stream().filter(code -> !TenantLanguages.SUPPORTED.contains(code)).distinct().forEach(code ->
                violations.add(new Violation("languages", "LANGUAGE_UNKNOWN", "Not a supported language: " + code)));
        if (!proposed.contains(TenantLanguages.ENGLISH)) {
            violations.add(new Violation("languages", "LANGUAGE_ENGLISH_REQUIRED", "English is always offered."));
        }
        if (!TenantLanguages.ENGLISH.equals(defaultLanguage) && !proposed.contains(defaultLanguage)) {
            violations.add(new Violation("languages", "LANGUAGE_DEFAULT_REQUIRED",
                    "The tenant's default language (" + defaultLanguage + ") must stay on."));
        }
        ValidationFailedException.throwIfAny("The languages are invalid.", violations);
        return TenantLanguages.SUPPORTED.stream().filter(proposed::contains).toList();
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Set<String> candidateRoles() {
        return Set.of(Roles.TENANT_ADMIN);
    }

    @Override
    public boolean canDecide(PendingChangeView change) {
        return support.canAdministerMetadata();
    }

    @Override
    public ApplyResult apply(PendingChangeView change) {
        if (change.operation() != ChangeOperation.UPDATE) {
            throw new IllegalStateException("Languages are only updated");
        }
        List<String> languages = support.payload(change, LanguagesPayload.class).languages();
        if (!tenantLanguages.change(change.subjectId(), change.baseVersion(), languages)) {
            return ApplyResult.stale("The tenant's languages were changed after this change was proposed.");
        }
        support.auditApplied("TENANT_LANGUAGES_CHANGED", "TENANT", change.subjectId(), change,
                Map.of("languages", String.join(",", languages)));
        return ApplyResult.ok();
    }

    private static UUID tenantId() {
        return RequestContext.require().tenantId();
    }
}
