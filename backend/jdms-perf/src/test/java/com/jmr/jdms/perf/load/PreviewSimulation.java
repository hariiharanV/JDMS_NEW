package com.jmr.jdms.perf.load;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * First-page preview under concurrent load (spec §7: p95 below 2 s): what the viewer fetches when a document is opened
 * (the preview PDF, or the original image) and the thumbnail shown in lists, for documents processed by the worker
 * (run IngestSimulation first; it leaves uploads titled "Load test upload").
 *
 * <p>{@code -Drate=<document opens per second>} (default 5), {@code -Dduration=<seconds>} (default 180).
 */
public class PreviewSimulation extends Simulation {

    static final int RATE = LoadSettings.intProperty("rate", 5);
    static final Duration DURATION = LoadSettings.seconds("duration", 180);
    static final String USER = "perfmaker1";

    final HttpProtocolBuilder protocol = http.baseUrl(LoadSettings.CORE).shareConnections();
    final List<Map<String, Object>> documents = processedUploads();

    final ScenarioBuilder opens = scenario("Opening processed documents")
            .exec(session -> session.setAll(documents.get(ThreadLocalRandom.current().nextInt(documents.size()))))
            .exec(http("first-page preview").get("/api/v1/documents/#{id}/versions/#{versionId}/preview")
                    .header("Authorization", session -> "Bearer " + Tokens.of(USER))
                    .header("Accept", "application/pdf,image/*").check(status().is(200)))
            .exec(http("thumbnail").get("/api/v1/documents/#{id}/versions/#{versionId}/thumbnail")
                    .header("Authorization", session -> "Bearer " + Tokens.of(USER))
                    .header("Accept", "image/png").check(status().is(200)));

    {
        setUp(opens.injectOpen(constantUsersPerSec(RATE).during(DURATION))).protocols(protocol)
                .assertions(global().failedRequests().percent().lt(1.0),
                        details("first-page preview").responseTime().percentile(95.0).lt(2_000));
    }

    /** Uploads of the ingest simulation whose current version has been processed. */
    static List<Map<String, Object>> processedUploads() {
        JsonMapper json = JsonMapper.builder().build();
        HttpClient client = HttpClient.newHttpClient();
        List<Map<String, Object>> found = new ArrayList<>();
        try {
            JsonNode hits = json.readTree(send(client, HttpRequest.newBuilder(URI.create(LoadSettings.CORE
                    + "/api/v1/search")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"Load test upload\",\"size\":100}"))))
                    .path("items");
            for (JsonNode hit : hits) {
                String id = hit.path("id").asString();
                JsonNode version = json.readTree(send(client, HttpRequest.newBuilder(URI.create(LoadSettings.CORE
                        + "/api/v1/documents/" + id + "/versions")).GET())).path(0);
                if ("DONE".equals(version.path("processingStatus").asString())) {
                    found.add(Map.of("id", id, "versionId", version.path("id").asString()));
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not list the processed uploads", e);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("No processed uploads: run IngestSimulation first");
        }
        return found;
    }

    private static String send(HttpClient client, HttpRequest.Builder request) throws IOException,
            InterruptedException {
        return client.send(request.header("Authorization", "Bearer " + Tokens.of(USER)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
