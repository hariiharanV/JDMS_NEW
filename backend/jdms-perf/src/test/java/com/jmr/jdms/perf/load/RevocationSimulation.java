package com.jmr.jdms.perf.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.asLongAsDuring;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.repeat;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Permission revocation (spec §7: effective within 60 s on every path): a security admin proposes a DENY rule on a
 * document for the VIEWER role, a second one approves it, and a viewer keeps opening and searching for the document
 * until both paths refuse it; the time from the approval to both paths refusing is written to
 * {@code target/gatling/revocationsimulation-latencies.json} (target: below 60 s). The rules stay in the
 * synthetic tenant (each run picks other documents).
 *
 * <p>{@code -Drounds=<n>} (default 3).
 */
public class RevocationSimulation extends Simulation {

    static final int ROUNDS = LoadSettings.intProperty("rounds", 3);
    static final String METRIC = "revocation effective (detail and search)";
    static final Latencies LATENCIES = new Latencies("revocationsimulation").target(METRIC, 60_000);

    final HttpProtocolBuilder protocol = http.baseUrl(LoadSettings.CORE).acceptHeader("application/json")
            .contentTypeHeader("application/json").shareConnections();

    static String bearer(String user) {
        return "Bearer " + Tokens.of(user);
    }

    static Session denied(Session session) {
        return session.set("denied", session.getInt("detailStatus") == 404 && !session.getString("hits")
                .contains(session.getString("id")));
    }

    final ScenarioBuilder revocations = scenario("Revoking a viewer's access").exec(repeat(ROUNDS, "counter").on(
            exec(session -> session.set("word", List.of("guarantor", "valuation", "mortgage", "insured", "bureau")
                    .get(session.getInt("counter") % 5)).set("denied", false))
            .exec(http("pick a visible document").post("/api/v1/search")
                    .header("Authorization", session -> bearer("perfviewer1"))
                    .body(StringBody("{\"query\":\"#{word}\",\"size\":1,\"page\":#{counter}}"))
                    .check(status().is(200), jsonPath("$.items[0].id").saveAs("id"),
                            jsonPath("$.items[0].cif").saveAs("cif")))
            .exec(http("propose DENY rule").post("/api/v1/admin/access-rules")
                    .header("Authorization", session -> bearer("perfsec1"))
                    .header("Idempotency-Key", session -> UUID.randomUUID().toString())
                    .body(StringBody("{\"scopeType\":\"DOCUMENT\",\"scopeId\":\"#{id}\",\"principalType\":\"ROLE\","
                            + "\"principalId\":\"VIEWER\",\"actions\":[\"VIEW_METADATA\",\"VIEW_CONTENT\","
                            + "\"DOWNLOAD\"],\"effect\":\"DENY\",\"description\":\"B-13 revocation measurement\"}"))
                    .check(status().is(202), jsonPath("$.id").saveAs("change")))
            .exec(http("approve DENY rule").post("/api/v1/admin/pending-changes/#{change}/approve")
                    .header("Authorization", session -> bearer("perfsec2"))
                    .header("Idempotency-Key", session -> UUID.randomUUID().toString())
                    .body(StringBody("{\"comment\":\"B-13 revocation measurement\"}")).check(status().is(200)))
            .exec(session -> session.set("approvedAt", System.currentTimeMillis()))
            .asLongAsDuring(session -> !session.getBoolean("denied"), Duration.ofSeconds(90)).on(
                    exec(http("viewer opens the document").get("/api/v1/documents/#{id}")
                            .header("Authorization", session -> bearer("perfviewer1"))
                            .check(status().in(200, 404).saveAs("detailStatus")))
                    .exec(http("viewer searches for it").post("/api/v1/search")
                            .header("Authorization", session -> bearer("perfviewer1"))
                            .body(StringBody("{\"filters\":{\"cif\":\"#{cif}\"},\"size\":100}"))
                            .check(status().is(200), jsonPath("$.items[*].id").findAll()
                                    .transform(Object::toString).withDefault("[]").saveAs("hits")))
                    .exec(RevocationSimulation::denied)
                    .doIf(session -> !session.getBoolean("denied")).then(pause(Duration.ofMillis(500))))
            .exec(session -> {
                if (!session.getBoolean("denied")) {
                    LATENCIES.missed(METRIC);
                    return session.markAsFailed();
                }
                LATENCIES.record(METRIC, System.currentTimeMillis() - session.getLong("approvedAt"));
                return session;
            })));

    @Override
    public void after() {
        LATENCIES.report();
    }

    {
        Tokens.of("perfviewer1");
        Tokens.of("perfsec1");
        Tokens.of("perfsec2");
        setUp(revocations.injectOpen(atOnceUsers(1))).protocols(protocol)
                .assertions(global().failedRequests().percent().is(0.0));
    }
}
