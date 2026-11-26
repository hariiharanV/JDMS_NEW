package com.jmr.jdms.workflow.domain;

import java.util.List;

/** The ordered stages of a multi-stage approval, as produced by the {@code approval-stages} routing table. */
record StagePlan(List<Stage> stages) {

    record Stage(String key, String name, List<String> candidateRoles, int slaHours) {
    }

    Stage stage(String key) {
        return stages.stream().filter(stage -> stage.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Stage " + key + " is not in the plan"));
    }

    List<String> keys() {
        return stages.stream().map(Stage::key).toList();
    }
}
