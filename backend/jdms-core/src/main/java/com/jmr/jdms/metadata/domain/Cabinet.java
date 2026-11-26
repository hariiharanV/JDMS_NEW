package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A top-level area of documents (legacy application / {@code appln_id}, e.g. CUSTOMERS, LOANS). */
@Entity
@Table(name = "cabinet")
public class Cabinet extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "max_file_size_mb", nullable = false)
    private int maxFileSizeMb;

    @Column(name = "quota_mb", nullable = false)
    private long quotaMb;

    @Column(name = "default_process_key", length = 100)
    private String defaultProcessKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MetadataStatus status;

    protected Cabinet() {
    }

    public Cabinet(UUID id, String code, String name, int maxFileSizeMb, long quotaMb, String defaultProcessKey) {
        this.id = id;
        this.code = code;
        this.status = MetadataStatus.ACTIVE;
        update(name, maxFileSizeMb, quotaMb, defaultProcessKey, MetadataStatus.ACTIVE);
    }

    public void update(String newName, int newMaxFileSizeMb, long newQuotaMb, String newDefaultProcessKey,
            MetadataStatus newStatus) {
        this.name = newName;
        this.maxFileSizeMb = newMaxFileSizeMb;
        this.quotaMb = newQuotaMb;
        this.defaultProcessKey = newDefaultProcessKey;
        this.status = newStatus;
    }

    public void deactivate() {
        this.status = MetadataStatus.INACTIVE;
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

    public int getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    public long getQuotaMb() {
        return quotaMb;
    }

    public String getDefaultProcessKey() {
        return defaultProcessKey;
    }

    public MetadataStatus getStatus() {
        return status;
    }
}
