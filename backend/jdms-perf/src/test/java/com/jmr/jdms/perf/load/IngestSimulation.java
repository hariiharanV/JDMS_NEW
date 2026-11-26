package com.jmr.jdms.perf.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.ByteArrayBodyPart;
import static io.gatling.javaapi.http.HttpDsl.StringBodyPart;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Uploads through the API while the worker processes them (spec §7): metadata searchable immediately after the upload
 * commits, content searchable p95 below 2 minutes for digital files and below 10 minutes for scans (OCR), first-page
 * preview p95 below 2 s once processed. Every upload carries a unique CIF (metadata check) and a unique word in its
 * content (content check). The times from the upload's response until each check succeeds are wall-clock latencies
 * ({@link Latencies}, written to {@code target/gatling/ingestsimulation-latencies.json}); "immediately" is taken as
 * p95 below 2 s (indexing runs right after the commit, asynchronously).
 *
 * <p>{@code -Drate=<uploads per second>} (default 0.5), {@code -Dduration=<seconds>} (default 600),
 * {@code -DscanShare=<0..1>} (default 0.2), {@code -Dfollow=false} to upload only (throughput: read the pipeline's
 * drain rate from the database, docs/perf/README.md).
 */
public class IngestSimulation extends Simulation {

    static final double RATE = LoadSettings.doubleProperty("rate", 0.5);
    static final Duration DURATION = LoadSettings.seconds("duration", 600);
    static final double SCAN_SHARE = LoadSettings.doubleProperty("scanShare", 0.2);
    /** false: upload only (throughput runs; the pipeline's drain rate is then read from the database) */
    static final boolean FOLLOW = Boolean.parseBoolean(LoadSettings.property("follow", "true"));
    static final String MAKER = "perfmaker1";

    final HttpProtocolBuilder protocol = http.baseUrl(LoadSettings.CORE).acceptHeader("application/json")
            .shareConnections();

    static Session prepare(Session session) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean scan = random.nextDouble() < SCAN_SHARE;
        StringBuilder marker = new StringBuilder("zq");
        for (int i = 0; i < 10; i++) {
            marker.append((char) ('a' + random.nextInt(26)));
        }
        String cif = String.format(Locale.ROOT, "L%011d", random.nextLong(100_000_000_000L));
        byte[] file = scan ? SampleFiles.scan(marker.toString(), random.nextLong())
                : SampleFiles.pdf(marker.toString(), 1 + random.nextInt(4), random.nextLong());
        // the marker is only in the content: in the title it would make the content check a metadata check
        String metadata = "{\"documentTypeCode\":\"ACCOUNT_OPENING_FORM\",\"title\":\"Load test upload " + cif
                + "\",\"branchCode\":\"001\",\"cif\":\"" + cif + "\",\"accountNo\":\"A" + cif.substring(1)
                + "\",\"customFields\":{\"account_type\":\"SAVINGS\",\"date_opened\":\"2026-10-01\"}}";
        return session.set("kind", scan ? "scan" : "digital").set("marker", marker.toString()).set("cif", cif)
                .set("file", file).set("fileName", scan ? "scan.png" : "form.pdf")
                .set("mime", scan ? "image/png" : "application/pdf").set("metadata", metadata).set("found", 0);
    }

    static String bearer(Session session) {
        return "Bearer " + Tokens.of(MAKER);
    }

    static final ChainBuilder upload = exec(http("upload").post("/api/v1/documents")
            .header("Authorization", IngestSimulation::bearer)
            .header("Idempotency-Key", session -> UUID.randomUUID().toString())
            .bodyParts(StringBodyPart("metadata", session -> session.getString("metadata"))
                            .contentType("application/json"),
                    ByteArrayBodyPart("file", session -> (byte[]) session.get("file"))
                            .fileName(session -> session.getString("fileName"))
                            .contentType(session -> session.getString("mime")))
            .asMultipartForm()
            .check(status().is(201), jsonPath("$.id").saveAs("id")));

    static final Latencies LATENCIES = new Latencies("ingestsimulation")
            .target("metadata searchable after upload", 2_000)
            .target("content searchable (digital)", 120_000)
            .target("content searchable (scan)", 600_000);

    /** Polls {@code search} until it finds the upload; records the time since the upload returned. */
    static ChainBuilder until(String metric, String requestName, Function<Session, String> body, Duration every,
            Duration limit) {
        return exec(session -> session.set("found", 0))
                .asLongAsDuring(session -> session.getInt("found") < 1, limit).on(
                        exec(http(requestName).post("/api/v1/search").header("Authorization", IngestSimulation::bearer)
                                .header("Content-Type", "application/json").body(StringBody(body))
                                .check(status().is(200), jsonPath("$.total").ofInt().saveAs("found")))
                        .doIf(session -> session.getInt("found") < 1).then(pause(every)))
                .exec(session -> {
                    if (session.getInt("found") < 1) {
                        LATENCIES.missed(metric);
                        return session.markAsFailed();
                    }
                    LATENCIES.record(metric, System.currentTimeMillis() - session.getLong("uploadedAt"));
                    return session;
                });
    }

    static final ChainBuilder metadataSearchable = until("metadata searchable after upload",
            "metadata search (by CIF)", session -> "{\"filters\":{\"cif\":\"" + session.getString("cif") + "\"}}",
            Duration.ofMillis(200), Duration.ofSeconds(30));

    static ChainBuilder contentSearchable(String kind, Duration limit) {
        return until("content searchable (" + kind + ")", "content search (" + kind + ")",
                session -> "{\"query\":\"" + session.getString("marker") + "\",\"filters\":{\"cif\":\""
                        + session.getString("cif") + "\"}}", Duration.ofSeconds(2), limit);
    }

    /** Waits until the version is processed (renditions done), then opens the preview as the viewer does. */
    static final ChainBuilder preview = exec(session -> session.set("processing", "PENDING"))
            .asLongAsDuring(session -> !"DONE".equals(session.getString("processing")), Duration.ofMinutes(12)).on(
                    exec(http("versions").get("/api/v1/documents/#{id}/versions")
                            .header("Authorization", IngestSimulation::bearer)
                            .check(status().is(200), jsonPath("$[0].id").saveAs("versionId"),
                                    jsonPath("$[0].processingStatus").saveAs("processing")))
                    .doIf(session -> !"DONE".equals(session.getString("processing"))).then(pause(Duration.ofSeconds(2))))
            .exec(http("first-page preview").get("/api/v1/documents/#{id}/versions/#{versionId}/preview")
                    .header("Authorization", IngestSimulation::bearer).header("Accept", "application/pdf,image/*")
                    .check(status().is(200)));

    final ScenarioBuilder uploads = scenario("Uploads processed by the worker")
            .exec(IngestSimulation::prepare)
            .exec(upload)
            .exitHereIfFailed() // a failed upload has nothing to wait for
            .doIf(session -> FOLLOW).then(exec(session -> session.set("uploadedAt", System.currentTimeMillis()))
                    .exec(metadataSearchable)
                    .doIfEqualsOrElse("#{kind}", "scan")
                    .then(contentSearchable("scan", Duration.ofMinutes(12)))
                    .orElse(contentSearchable("digital", Duration.ofMinutes(4)))
                    .exec(preview));

    @Override
    public void after() {
        LATENCIES.report();
    }

    {
        Tokens.of(MAKER);
        setUp(uploads.injectOpen(constantUsersPerSec(RATE).during(DURATION))).protocols(protocol)
                .maxDuration(DURATION.plus(Duration.ofMinutes(15)))
                .assertions(FOLLOW
                        ? List.of(global().failedRequests().percent().lt(1.0),
                                details("upload").failedRequests().percent().is(0.0),
                                details("first-page preview").responseTime().percentile(95.0).lt(2_000))
                        : List.of(details("upload").failedRequests().percent().is(0.0)));
    }
}
