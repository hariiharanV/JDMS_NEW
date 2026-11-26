package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A committee that votes on items routed to {@code committee-approval} (spec §4.4). Legacy CM_COMMITTEE_MASTER. */
@Entity
@Table(name = "committee")
public class Committee extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 50, updatable = false)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "min_quorum", nullable = false)
    private int minQuorum;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommitteeStatus status;

    protected Committee() {
    }

    public Committee(UUID id, String code, String name, int minQuorum) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.minQuorum = minQuorum;
        this.status = CommitteeStatus.ACTIVE;
    }

    void update(String name, int minQuorum, CommitteeStatus status) {
        this.name = name;
        this.minQuorum = minQuorum;
        this.status = status;
    }

    void deactivate() {
        this.status = CommitteeStatus.INACTIVE;
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

    public int getMinQuorum() {
        return minQuorum;
    }

    public CommitteeStatus getStatus() {
        return status;
    }
}
