package com.jmr.jdms.tenant.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A branch of a bank (legacy {@code brn_cd}). Codes are unique per tenant. */
@Entity
@Table(name = "branch")
public class Branch extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 100)
    private String region;

    protected Branch() {
    }

    public Branch(String code, String name, String region) {
        this.id = Ids.newId();
        this.code = code;
        this.name = name;
        this.region = region;
    }

    public void rename(String newName) {
        this.name = newName;
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

    public String getRegion() {
        return region;
    }
}
