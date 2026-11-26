package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A role (legacy {@code CM_ROLEMASTER}). Mirrors Keycloak realm roles seen in tokens; Keycloak assigns them. */
@Entity
@Table(name = "role")
public class Role extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String description;

    protected Role() {
    }

    public Role(String code, String name, String description) {
        this.id = Ids.newId();
        this.code = code;
        this.name = name;
        this.description = description;
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

    public String getDescription() {
        return description;
    }
}
