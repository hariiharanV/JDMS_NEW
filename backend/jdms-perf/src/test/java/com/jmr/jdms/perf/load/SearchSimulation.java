package com.jmr.jdms.perf.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Search response time on the synthetic tenant (spec §7: p95 below 1.5 s on 1 million documents). A mix of what bank
 * users search for: English and Arabic words with facets, a customer's documents by CIF, filters with a date range,
 * and the same searches by a user restricted to three branches and INTERNAL clearance (the access filter at work).
 *
 * <p>{@code -Drate=<searches per second>} (default 10), {@code -Dduration=<seconds>} (default 300).
 */
public class SearchSimulation extends Simulation {

    static final int RATE = LoadSettings.intProperty("rate", 10);
    static final Duration DURATION = LoadSettings.seconds("duration", 300);
    static final int CUSTOMERS = LoadSettings.intProperty("customers", 200_000);

    static final List<String> ENGLISH = List.of("guarantor", "valuation", "instalments", "signatures", "salary",
            "collateral", "passport", "overdraft", "deposit", "mortgage", "insured", "statements", "bureau",
            "committee", "cheque", "settlement", "residency", "vehicle", "employment", "Muscat", "Sohar", "Salalah",
            "liability", "identity", "maturity", "exchange", "address", "screening");
    static final List<String> ARABIC = List.of("الكفيل", "التقييم", "قسطاً", "التواقيع", "الراتب", "الضمان", "جواز",
            "الوديعة", "رهن", "التأمين", "الشيكات", "السداد", "العقار", "الائتمان", "مسقط", "صلالة", "الفرع",
            "البنك");
    static final List<String> TYPES = List.of("PASSPORT", "NATIONAL_ID", "ACCOUNT_OPENING_FORM", "LOAN_AGREEMENT",
            "COLLATERAL_DEED", "SALARY_CERTIFICATE");

    final HttpProtocolBuilder protocol = http.baseUrl(LoadSettings.CORE).contentTypeHeader("application/json")
            .acceptHeader("application/json").shareConnections();

    static ChainBuilder search(String name, String user) {
        return exec(http(name).post("/api/v1/search")
                .header("Authorization", session -> "Bearer " + Tokens.of(user))
                .body(StringBody(session -> session.getString("body")))
                .check(status().is(200), jsonPath("$.total").ofInt().saveAs("total")));
    }

    static ChainBuilder body(java.util.function.Supplier<String> body) {
        return exec(session -> session.set("body", body.get()));
    }

    static String words(List<String> pool) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String first = pool.get(random.nextInt(pool.size()));
        return random.nextBoolean() ? first : first + " " + pool.get(random.nextInt(pool.size()));
    }

    static String text(List<String> pool) {
        return "{\"query\":\"" + words(pool) + "\",\"facets\":[\"documentType\",\"branch\"],\"size\":20}";
    }

    static String byCif() {
        return String.format(Locale.ROOT, "{\"filters\":{\"cif\":\"C%08d\"},\"size\":20}",
                1 + ThreadLocalRandom.current().nextInt(CUSTOMERS));
    }

    static String filtered() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        LocalDate from = LocalDate.of(2021, 1, 1).plusDays(random.nextInt(1700));
        return "{\"filters\":{\"documentType\":\"" + TYPES.get(random.nextInt(TYPES.size())) + "\",\"branch\":\""
                + String.format(Locale.ROOT, "%03d", 1 + random.nextInt(20)) + "\",\"createdFrom\":\"" + from
                + "\",\"createdTo\":\"" + from.plusDays(365) + "\"},\"facets\":[\"status\"],\"size\":20}";
    }

    final ScenarioBuilder searches = scenario("Bank users searching").exec(randomSwitch().on(
            percent(40.0).then(body(() -> text(ENGLISH)).exec(search("search: words (en)", "perfviewer1"))),
            percent(15.0).then(body(() -> text(ARABIC)).exec(search("search: words (ar)", "perfviewer1"))),
            percent(25.0).then(body(SearchSimulation::byCif).exec(search("search: by CIF", "perfviewer1"))),
            percent(10.0).then(body(SearchSimulation::filtered).exec(search("search: filters and date range",
                    "perfviewer1"))),
            percent(10.0).then(body(() -> text(ENGLISH)).exec(search("search: words, 3 branches + INTERNAL",
                    "perfbranch1")))));

    {
        Tokens.of("perfviewer1");
        Tokens.of("perfbranch1");
        setUp(searches.injectOpen(rampUsersPerSec(1).to(RATE).during(Duration.ofSeconds(30)),
                constantUsersPerSec(RATE).during(DURATION)))
                .protocols(protocol)
                .assertions(global().failedRequests().percent().lt(1.0),
                        global().responseTime().percentile(95.0).lt(1500),
                        details("search: words (en)").responseTime().percentile(95.0).lt(1500),
                        details("search: by CIF").responseTime().percentile(95.0).lt(1500));
    }
}
