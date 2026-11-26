package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ScopeType;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An approved access rule (legacy {@code CM_ROLEPERMISSIONS} and per-item {@code doc_permission}). Rows exist only
 * once approved: proposals live in {@code pending_change} (maker-checker).
 */
@Entity
@Table(name = "access_rule")
public class AccessRule extends TenantScopedEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 10)
    private PrincipalKind principalType;

    @Column(name = "principal_id", nullable = false, length = 64)
    private String principalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> actions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Effect effect;

    @Enumerated(EnumType.STRING)
    @Column(name = "branch_constraint", nullable = false, length = 10)
    private BranchConstraint branchConstraint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "branch_ids", nullable = false)
    private List<String> branchIds;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "approval_status", nullable = false, length = 20)
    private String approvalStatus;

    @Column(length = 500)
    private String description;

    protected AccessRule() {
    }

    public AccessRule(UUID id, RuleSpec spec) {
        this.id = id == null ? Ids.newId() : id;
        this.approvalStatus = "APPROVED";
        update(spec);
    }

    public void update(RuleSpec spec) {
        this.scopeType = spec.scopeType();
        this.scopeId = spec.scopeId();
        this.principalType = spec.principalType();
        this.principalId = spec.principalId();
        this.actions = spec.actions().stream().map(Enum::name).sorted().toList();
        this.effect = spec.effect();
        this.branchConstraint = spec.branchConstraint();
        this.branchIds = spec.branchIds().stream().map(UUID::toString).sorted().toList();
        this.validFrom = spec.validFrom();
        this.validTo = spec.validTo();
        this.description = spec.description();
    }

    public RuleSpec spec() {
        return new RuleSpec(scopeType, scopeId, principalType, principalId,
                actions.stream().map(Action::valueOf).collect(Collectors.toSet()), effect, branchConstraint,
                branchIds.stream().map(UUID::fromString).collect(Collectors.toSet()), validFrom, validTo, description);
    }

    CompiledRule compile() {
        RuleSpec spec = spec();
        return new CompiledRule(id, spec.scopeType(), spec.scopeId(), spec.principalType(), spec.principalId(),
                spec.actions(), spec.effect(), spec.branchConstraint(), spec.branchIds(), validFrom, validTo);
    }

    public UUID getId() {
        return id;
    }
}
