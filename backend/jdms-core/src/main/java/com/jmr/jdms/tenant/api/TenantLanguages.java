package com.jmr.jdms.tenant.api;

import java.util.List;
import java.util.UUID;

/**
 * The languages a tenant's screens offer (ADR-0023). English is always on; Arabic is optional. Changed only by an
 * approved change of the tenant's administrators.
 */
public interface TenantLanguages {

    String ENGLISH = "en";
    String ARABIC = "ar";
    /** Every language the screens can offer, in the order they are listed. */
    List<String> SUPPORTED = List.of(ENGLISH, ARABIC);

    LanguageSettings get(UUID tenantId);

    /** Sets the languages; {@code false}, and nothing changed, when the tenant changed since {@code version}. */
    boolean change(UUID tenantId, long version, List<String> languages);

    /** The tenant's languages, its default language, and the version a proposed change is based on. */
    record LanguageSettings(List<String> languages, String defaultLocale, long version) {
    }
}
