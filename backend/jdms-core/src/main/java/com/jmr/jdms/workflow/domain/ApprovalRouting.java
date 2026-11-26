package com.jmr.jdms.workflow.domain;

import static com.jmr.jdms.workflow.domain.ApprovalVariables.APPROVER_ROLES;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.COMMITTEE_CODE;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.COMMITTEE_ID;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.COMMITTEE_MEMBERS;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.COMMITTEE_NAME;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.QUORUM;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.SLA_HOURS;
import static com.jmr.jdms.workflow.domain.ApprovalVariables.STAGE_PLAN;

import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.security.Roles;
import com.jmr.jdms.workflow.api.ApprovalProcesses;
import com.jmr.jdms.workflow.api.ApproverDirectory;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flowable.dmn.api.DmnDecisionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides who approves an item (spec §4.4): the approver roles of a single approval, the stages of a multi-stage
 * approval (DMN table {@code approval-stages}) or the committee and its members (DMN table {@code
 * approval-committee}). The DMN tables are deployed without a tenant; a tenant-specific deployment overrides them.
 */
@Component
class ApprovalRouting {

    static final String STAGES_DECISION = "approval-stages";
    static final String COMMITTEE_DECISION = "approval-committee";
    static final String APPROVAL_ROUTING_FAILED = "APPROVAL_ROUTING_FAILED";
    static final String COMMITTEE_QUORUM_UNREACHABLE = "COMMITTEE_QUORUM_UNREACHABLE";

    /** Facts every routing table can rely on (null when unknown; {@code amount} 0). */
    static final List<String> FACTS = List.of("documentTypeCode", "documentCategory", "branchCode", "classification");

    private final DmnDecisionService decisions;
    private final CommitteeRepository committees;
    private final CommitteeMemberRepository members;
    private final ApproverDirectory directory;
    private final JsonMapper jsonMapper;
    private final int defaultSlaHours;

    ApprovalRouting(DmnDecisionService decisions, CommitteeRepository committees, CommitteeMemberRepository members,
            ApproverDirectory directory, JsonMapper jsonMapper,
            @Value("${jdms.workflow.default-sla-hours:24}") int defaultSlaHours) {
        this.decisions = decisions;
        this.committees = committees;
        this.members = members;
        this.directory = directory;
        this.jsonMapper = jsonMapper;
        this.defaultSlaHours = defaultSlaHours;
    }

    /**
     * Process variables that route the approval.
     *
     * @param approverRoles candidate roles for {@code single-approval}; empty means {@code CHECKER}
     */
    Map<String, Object> route(String processKey, Map<String, Object> facts, UUID makerId, Set<String> approverRoles) {
        return switch (processKey) {
            case ApprovalProcesses.SINGLE -> Map.of(
                    APPROVER_ROLES, String.join(",", approverRoles.isEmpty() ? Set.of(Roles.CHECKER) : approverRoles),
                    SLA_HOURS, defaultSlaHours);
            case ApprovalProcesses.MULTI_STAGE -> Map.of(STAGE_PLAN, jsonMapper.writeValueAsString(stages(facts)));
            case ApprovalProcesses.COMMITTEE -> committee(facts, makerId);
            default -> throw new IllegalArgumentException("Unknown approval process " + processKey);
        };
    }

    StagePlan stages(Map<String, Object> facts) {
        List<Map<String, Object>> rows = decisions.createExecuteDecisionBuilder().decisionKey(STAGES_DECISION)
                .tenantId(tenant()).fallbackToDefaultTenant().variables(normalise(facts)).executeDecision();
        List<StagePlan.Stage> stages = rows.stream()
                .sorted(Comparator.comparingInt(row -> number(row.get("stageOrder"))))
                .map(row -> new StagePlan.Stage(text(row.get("stageKey")), text(row.get("stageName")),
                        roles(row.get("candidateRoles")), number(row.get("slaHours"))))
                .toList();
        if (stages.isEmpty() || stages.stream().anyMatch(stage -> stage.key() == null
                || stage.candidateRoles().isEmpty())) {
            throw routingFailed("The approval-stages routing table produced no usable stage for this item.");
        }
        if (stages.stream().map(StagePlan.Stage::key).distinct().count() != stages.size()) {
            throw routingFailed("The approval-stages routing table produced the same stage twice.");
        }
        return new StagePlan(stages);
    }

    StagePlan plan(String json) {
        return jsonMapper.readValue(json, StagePlan.class);
    }

    String json(StagePlan plan) {
        return jsonMapper.writeValueAsString(plan);
    }

    private Map<String, Object> committee(Map<String, Object> facts, UUID makerId) {
        Map<String, Object> row = decisions.createExecuteDecisionBuilder().decisionKey(COMMITTEE_DECISION)
                .tenantId(tenant()).fallbackToDefaultTenant().variables(normalise(facts))
                .executeDecisionWithSingleResult();
        String code = row == null ? null : text(row.get("committeeCode"));
        Committee committee = code == null ? null : committees.findByCode(code)
                .filter(found -> found.getStatus() == CommitteeStatus.ACTIVE).orElse(null);
        if (committee == null) {
            throw routingFailed("No active committee is configured for this item" + (code == null ? "." : " (" + code
                    + ")."));
        }
        // The maker never votes on their own item; inactive users cannot vote
        List<String> voters = members.findByCommitteeIdOrderByCreatedAtAscIdAsc(committee.getId()).stream()
                .map(CommitteeMember::getUserId).filter(user -> !user.equals(makerId))
                .filter(directory::isActiveUser).map(UUID::toString).toList();
        if (voters.size() < committee.getMinQuorum()) {
            throw new ApiException(HttpStatus.CONFLICT, COMMITTEE_QUORUM_UNREACHABLE, "Committee " + committee.getCode()
                    + " has fewer eligible members than its quorum, so it could never approve this item.");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(COMMITTEE_ID, committee.getId().toString());
        variables.put(COMMITTEE_CODE, committee.getCode());
        variables.put(COMMITTEE_NAME, committee.getName());
        variables.put(COMMITTEE_MEMBERS, String.join(",", voters));
        variables.put(QUORUM, committee.getMinQuorum());
        variables.put(SLA_HOURS, row.get("slaHours") == null ? defaultSlaHours : number(row.get("slaHours")));
        return variables;
    }

    private static Map<String, Object> normalise(Map<String, Object> facts) {
        Map<String, Object> variables = new HashMap<>();
        FACTS.forEach(fact -> variables.put(fact, null));
        variables.put("amount", BigDecimal.ZERO);
        facts.forEach((name, value) -> {
            if (value != null) {
                variables.put(name, value);
            }
        });
        return variables;
    }

    private static List<String> roles(Object value) {
        String text = text(value);
        return text == null ? List.of() : Arrays.stream(text.split(",")).map(String::trim)
                .filter(role -> !role.isEmpty()).collect(Collectors.toList());
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String tenant() {
        return RequestContext.require().tenantId().toString();
    }

    private static ApiException routingFailed(String detail) {
        return new ApiException(HttpStatus.CONFLICT, APPROVAL_ROUTING_FAILED, detail);
    }
}
