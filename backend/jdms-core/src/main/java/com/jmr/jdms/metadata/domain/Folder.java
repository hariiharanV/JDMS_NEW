package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A virtual folder for browsing (legacy {@code DMS_FOLDER_DETAIL}, which was a disk directory). Storage never
 * depends on folders; {@code pathCache} is for display only and is never an identifier (CLAUDE.md rule 5).
 */
@Entity
@Table(name = "folder")
public class Folder extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "cabinet_id", nullable = false)
    private UUID cabinetId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "path_cache", nullable = false, length = 4000)
    private String pathCache;

    protected Folder() {
    }

    public Folder(UUID id, UUID cabinetId, UUID parentId, String name, String pathCache) {
        this.id = id;
        this.cabinetId = cabinetId;
        this.parentId = parentId;
        this.name = name;
        this.pathCache = pathCache;
    }

    public void moveTo(UUID newParentId, String newName, String newPathCache) {
        this.parentId = newParentId;
        this.name = newName;
        this.pathCache = newPathCache;
    }

    public void repath(String newPathCache) {
        this.pathCache = newPathCache;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCabinetId() {
        return cabinetId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public String getPathCache() {
        return pathCache;
    }
}
