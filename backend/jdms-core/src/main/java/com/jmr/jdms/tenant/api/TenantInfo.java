package com.jmr.jdms.tenant.api;

import java.util.List;
import java.util.UUID;

/** A tenant (bank) as seen by other modules; {@code languages} are the ones its screens offer (ADR-0023). */
public record TenantInfo(UUID id, String code, String name, String defaultLocale, List<String> languages) {
}
