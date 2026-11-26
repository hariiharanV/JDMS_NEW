package com.jmr.jdms.workflow.api;

import java.util.Set;

/** Keys of the reusable approval processes (spec §4.4); BPMN definitions in {@code classpath:processes/}. */
public final class ApprovalProcesses {

    public static final String SINGLE = "single-approval";
    public static final String MULTI_STAGE = "multi-stage-approval";
    public static final String COMMITTEE = "committee-approval";

    public static final Set<String> KEYS = Set.of(SINGLE, MULTI_STAGE, COMMITTEE);

    private ApprovalProcesses() {
    }
}
