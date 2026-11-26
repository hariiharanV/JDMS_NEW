package com.jmr.jdms.tenant.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A bank using JDMS Next (legacy {@code entity_id}). The only table without {@code tenant_id}: it is the tenant.
 * Tenants are provisioned by operations, not through the API, in the PoC.
 */
@Entity
@Table(name = "tenant")
public class Tenant extends AuditedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantStatus status;

    /** Reference to the tenant's key in the KMS (KeyService); never the key itself. */
    @Column(name = "kms_key_ref", length = 200)
    private String kmsKeyRef;

    @Column(name = "default_locale", nullable = false, length = 10)
    private String defaultLocale;

    /** Comma-separated language codes the screens offer, English first ({@code en} or {@code en,ar}; ADR-0023). */
    @Column(nullable = false, length = 50)
    private String languages;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String settings;

    protected Tenant() {
    }

    public Tenant(String code, String name, String defaultLocale) {
        this.id = Ids.newId();
        this.code = code;
        this.name = name;
        this.status = TenantStatus.ACTIVE;
        this.defaultLocale = defaultLocale;
        this.languages = "en";
        this.settings = "{}";
    }

    public void changeLanguages(List<String> newLanguages) {
        languages = String.join(",", newLanguages);
    }

    public void suspend() {
        status = TenantStatus.SUSPENDED;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public List<String> getLanguages() {
        return List.of(languages.split(","));
    }
}
