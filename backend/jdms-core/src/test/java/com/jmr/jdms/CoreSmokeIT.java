package com.jmr.jdms;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.jmr.jdms.TestcontainersConfiguration.OwnerJdbc;
import com.jmr.jdms.shared.web.RequestIdFilter;
import com.jmr.jdms.support.LogCapture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B-00 smoke test against a real PostgreSQL container: Flyway runs from {@code db/migration/postgresql}, health and
 * Prometheus metrics are served on the management port, and every request gets a request ID that appears in the
 * structured JSON log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class CoreSmokeIT {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    /** The migration history is visible to the schema owner only (ADR-0018). */
    @Autowired
    OwnerJdbc owner;

    @Test
    void flywayAppliesPostgresqlBaseline() {
        JdbcTemplate jdbc = owner.jdbc();
        Boolean applied = jdbc.queryForObject(
                "select success from flyway_schema_history where version = ?", Boolean.class, "1");
        String script = jdbc.queryForObject(
                "select script from flyway_schema_history where version = ?", String.class, "1");

        assertThat(applied).isTrue();
        assertThat(script).isEqualTo("V1__baseline.sql");
    }

    @Test
    void healthAndPrometheusAreOnTheManagementPort() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health", null);
        HttpResponse<String> prometheus = get(managementPort, "/actuator/prometheus", null);
        HttpResponse<String> healthOnApiPort = get(serverPort, "/actuator/health", null);

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("jvm_memory_used_bytes").contains("application=\"jdms-core\"");
        // the API port serves only authenticated /api/** requests
        assertThat(healthOnApiPort.statusCode()).isEqualTo(401);
    }

    @Test
    void requestIdIsEchoedAndLoggedAsStructuredJson() throws Exception {
        HttpResponse<String> response;
        List<String> lines;
        try (LogCapture logs = LogCapture.of(RequestIdFilter.class, Level.DEBUG)) {
            response = get(serverPort, "/api/v1/does-not-exist?cif=SECRET123", "smoke-request-0001");
            lines = logs.lines();
        }

        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("smoke-request-0001");
        assertThat(lines.stream().filter(line -> line.contains("\"requestId\":\"smoke-request-0001\"")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("{").endsWith("}").doesNotContain("SECRET123"));
    }

    @Test
    void requestIdIsGeneratedWhenMissing() throws Exception {
        HttpResponse<String> response = get(serverPort, "/api/v1/does-not-exist", null);

        assertThat(response.headers().firstValue("X-Request-Id")).hasValueSatisfying(id -> assertThat(id)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    private HttpResponse<String> get(int port, String path, String requestId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
