package com.jmr.jdms.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.worker.support.WorkerContainers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** The worker starts against real services and exposes health and metrics on its only HTTP port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkerContainers.class)
class WorkerSmokeIT {

    @LocalServerPort
    int port;

    @Test
    void healthAndPrometheusAreExposed() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> health = http.send(request("/actuator/health"), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> metrics = http.send(request("/actuator/prometheus"), HttpResponse.BodyHandlers.ofString());

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(metrics.body()).contains("application=\"jdms-worker\"");
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    }
}
