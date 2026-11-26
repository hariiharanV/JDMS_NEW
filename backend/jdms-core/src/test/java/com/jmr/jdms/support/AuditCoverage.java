package com.jmr.jdms.support;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;
import org.springframework.web.servlet.HandlerMapping;

/**
 * B-11 "every state-changing endpoint and every view/download writes an audit event", checked on every request any
 * integration test makes: a successful write ({@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}), document
 * view, content read, audit read or integration call must have left at least one audit event carrying the response's
 * {@code X-Request-Id}, or the test fails right there. Endpoints seen succeeding are remembered so
 * {@code AuditCoverageIT} can list any that no test exercises.
 */
public class AuditCoverage implements MockMvcBuilderCustomizer {

    /** Reads that are audited (spec §4.11: view, preview, download; reading the audit trail). */
    public static final Set<String> AUDITED_READS = Set.of(
            "GET /api/v1/documents/{id}",
            "GET /api/v1/documents/{documentId}/versions/{versionId}/content",
            "GET /api/v1/documents/{documentId}/versions/{versionId}/preview",
            "GET /api/v1/documents/{documentId}/versions/{versionId}/thumbnail",
            "GET /api/v1/documents/{id}/audit",
            "GET /api/v1/audit");

    private static final Set<String> COVERED = ConcurrentHashMap.newKeySet();

    private final JdbcTemplate jdbc;

    public AuditCoverage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Endpoints ({@code METHOD pattern}) that succeeded at least once and were audited. */
    public static Set<String> covered() {
        return new TreeSet<>(COVERED);
    }

    public static boolean isAudited(String method, String pattern) {
        return switch (method) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> pattern.startsWith("/integration/") || AUDITED_READS.contains(method + " " + pattern);
        };
    }

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.alwaysDo(this::check);
    }

    private void check(MvcResult result) {
        Object pattern = result.getRequest().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String method = result.getRequest().getMethod();
        int status = result.getResponse().getStatus();
        if (pattern == null || status >= 300 || !isAudited(method, pattern.toString())) {
            return;
        }
        String endpoint = method + " " + pattern;
        String requestId = result.getResponse().getHeader("X-Request-Id");
        Long events = jdbc.queryForObject("select count(*) from audit_event where request_id = ?", Long.class,
                requestId);
        if (events == null || events == 0) {
            throw new AssertionError(endpoint + " returned " + status + " without writing an audit event (request "
                    + requestId + ")");
        }
        COVERED.add(endpoint);
    }
}
