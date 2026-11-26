package com.jmr.jdms.search;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.DocumentGrants;
import com.jmr.jdms.access.api.GrantSource;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.ProcessingOutcome;
import com.jmr.jdms.contracts.ProcessingResult;
import com.jmr.jdms.contracts.ProcessingStep;
import com.jmr.jdms.contracts.RenditionBlob;
import com.jmr.jdms.document.api.IndexableDocument;
import com.jmr.jdms.document.api.IndexableDocuments;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.platform.jobs.JobStore;
import com.jmr.jdms.platform.jobs.NewJob;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-07 search against a real OpenSearch: metadata searchable right after commit and content after processing;
 * English and Arabic analysers; filters on banking keys and custom fields; the leakage suite (results, totals, facet
 * counts, snippets); permission changes within the stale-ACL window; a full rebuild from the database.
 */
@IntegrationTest
class SearchIT {

    static final Duration INDEXED = Duration.ofSeconds(20);

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired JobStore jobs;
    @Autowired EnvelopeEncryption encryption;
    @Autowired TransactionTemplate transactions;
    @Autowired JsonMapper jsonMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentGrants grants;
    @Autowired IndexableDocuments indexable;
    @Value("${jdms.search.url}") String openSearchUrl;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder maker;
    TestJwts.Builder maker2;
    TestJwts.Builder viewer;
    TestJwts.Builder viewer2;
    TestJwts.Builder records;

    @BeforeEach
    void setUp() {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001", "002");
        testMetadata.create(tenant);
        testMetadata.addSearchType(tenant);
        maker = user("maker", "MAKER", "001", "CONFIDENTIAL");
        maker2 = user("maker2", "MAKER", "002", "CONFIDENTIAL");
        viewer = user("viewer", "VIEWER", "001", "INTERNAL");
        viewer2 = user("viewer2", "VIEWER", "002", "INTERNAL");
        records = user("records", "RECORDS_OFFICER", "ALL", "RESTRICTED");
    }

    @Test
    void metadataIsSearchableRightAfterCommitByKeysAndCustomFields() throws Exception {
        String id = upload(maker, invoice("Invoice for Zephyr Trading", "001", "CIF777", "OMR", "urgent payment",
                null));

        JsonNode found = await().atMost(INDEXED).until(() -> search(viewer, "{\"query\":\"zephyr\"}"),
                result -> result.get("total").asInt() == 1);
        assertThat(found.at("/items/0/id").asString()).isEqualTo(id);
        assertThat(found.at("/items/0/documentTypeCode").asString()).isEqualTo("INVOICE");
        assertThat(total(viewer, "{\"filters\":{\"cif\":\"CIF777\"}}")).isEqualTo(1);
        assertThat(total(viewer, "{\"filters\":{\"cif\":\"CIF000\"}}")).isZero();
        assertThat(total(viewer, "{\"query\":\"CIF777\"}")).isEqualTo(1);
        assertThat(total(viewer, "{\"filters\":{\"accountNo\":\"ACC-CIF777\"}}")).isEqualTo(1);
        assertThat(total(viewer, "{\"filters\":{\"custom\":{\"currency\":\"OMR\"}}}")).isEqualTo(1);
        assertThat(total(viewer, "{\"filters\":{\"custom\":{\"currency\":\"USD\"}}}")).isZero();
        assertThat(total(viewer, "{\"query\":\"urgent\"}")).isEqualTo(1); // searchable custom text
        String today = java.time.LocalDate.now().toString();
        assertThat(total(viewer, "{\"filters\":{\"createdFrom\":\"" + today + "\",\"createdTo\":\"" + today
                + "\"}}")).isEqualTo(1);
    }

    @Test
    void sensitiveFieldsAreNeverSearchable() throws Exception {
        upload(maker, invoice("Card invoice", "001", "CIF1", "OMR", null, "4111222233334444"));
        await().atMost(INDEXED).until(() -> total(viewer, "{\"query\":\"card\"}") == 1);

        assertThat(total(viewer, "{\"query\":\"4111222233334444\"}")).isZero();
        api.as(viewer, searchRequest("{\"filters\":{\"custom\":{\"card_no\":\"4111222233334444\"}}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("FIELD_NOT_SEARCHABLE"));
    }

    @Test
    void contentIsSearchableOnceProcessedWithEnglishAndArabicAnalysers() throws Exception {
        String id = upload(maker, invoice("Scanned statement", "001", "CIF2", "OMR", null, null));
        await().atMost(INDEXED).until(() -> total(viewer, "{\"query\":\"scanned\"}") == 1);
        assertThat(total(viewer, "{\"query\":\"zebra\"}")).isZero();

        // as Tesseract writes Arabic: wrapped in bidi marks
        processed(id, "The customers received their monthly statements. \u200fكشف الحساب الشهري\u200e للعميل");

        JsonNode hit = await().atMost(INDEXED).until(() -> search(viewer, "{\"query\":\"zebra customer\"}"),
                result -> result.get("total").asInt() == 1).at("/items/0");
        assertThat(hit.get("id").asString()).isEqualTo(id);
        assertThat(total(viewer, "{\"query\":\"statement\"}")).isEqualTo(1); // English stemming: statements
        assertThat(total(viewer, "{\"query\":\"حساب\"}")).isEqualTo(1); // Arabic: الحساب
        assertThat(total(viewer, "{\"query\":\"الشهري\"}")).isEqualTo(1); // next to a bidi mark
        JsonNode snippets = search(viewer, "{\"query\":\"statements\"}").at("/items/0/snippets");
        assertThat(snippets.isEmpty()).isFalse();
        List<String> matches = new ArrayList<>();
        snippets.get(0).forEach(segment -> {
            if (segment.get("match").asBoolean()) {
                matches.add(segment.get("text").asString());
            }
        });
        assertThat(matches).contains("statements");
    }

    @Test
    void usersNeverSeeWhatTheyMayNotViewInResultsTotalsFacetsOrSnippets() throws Exception {
        String own = upload(maker, invoice("Common ledger alpha", "001", "CIF3", "OMR", null, null));
        String otherBranch = upload(maker2, invoice("Common ledger beta", "002", "CIF4", "USD", null, null));
        String confidential = upload(maker, invoice("Common ledger gamma", "001", "CIF5", "USD", null, null)
                .replace("\"title\"", "\"classification\":\"CONFIDENTIAL\",\"title\""));
        for (String id : List.of(own, otherBranch, confidential)) {
            processed(id, "Common secret figures for the quarter");
        }
        TestJwts.Builder head = user("head", "VIEWER", "ALL", "RESTRICTED");
        await().atMost(INDEXED).until(() -> total(head, "{\"query\":\"secret\"}") == 3);

        // viewer of branch 001 with INTERNAL clearance: only their own branch, below their clearance
        String facets = "{\"query\":\"common\",\"facets\":[\"branch\",\"documentType\",\"custom.currency\"]}";
        JsonNode result = search(viewer, facets);
        assertThat(result.get("total").asInt()).isEqualTo(1);
        assertThat(ids(result)).containsExactly(own);
        assertThat(result.at("/facets/branch")).hasSize(1);
        assertThat(result.at("/facets/branch/0/value").asString()).isEqualTo("001");
        assertThat(result.at("/facets/branch/0/count").asInt()).isEqualTo(1);
        assertThat(result.at("/facets/documentType/0/count").asInt()).isEqualTo(1);
        assertThat(result.at("/facets/custom.currency")).hasSize(1);
        assertThat(total(viewer, "{\"query\":\"secret\"}")).isEqualTo(1);
        assertThat(total(viewer, "{\"filters\":{\"cif\":\"CIF4\"}}")).isZero();
        assertThat(total(viewer2, "{\"query\":\"common\"}")).isEqualTo(1);

        // metadata only (no VIEW_CONTENT): found by metadata, never by content, never with snippets
        TestJwts.Builder auditor = user("auditor", "AUDITOR", "ALL", "RESTRICTED");
        jdbc.update("""
                insert into access_rule (id, tenant_id, scope_type, scope_id, principal_type, principal_id, actions,
                    effect, branch_constraint, branch_ids, approval_status, created_at, created_by, updated_at,
                    updated_by, version_lock)
                values (?, ?, 'TENANT', ?, 'ROLE', 'AUDITOR', '["VIEW_METADATA"]'::jsonb, 'ALLOW', 'ALL',
                    '[]'::jsonb, 'APPROVED', now(), 'test', now(), 'test', 0)
                """, UUID.randomUUID(), tenant.id(), tenant.id());
        await().atMost(Duration.ofSeconds(10)).until(() -> total(auditor, "{\"query\":\"common\"}") == 3);
        assertThat(total(auditor, "{\"query\":\"secret\"}")).isZero();
        JsonNode metadataOnly = search(auditor, "{\"query\":\"common\"}");
        metadataOnly.get("items").forEach(item -> assertThat(item.get("snippets").isEmpty()).isTrue());
        assertThat(search(head, "{\"query\":\"common\"}").at("/items/0/snippets").isEmpty()).isFalse();

        // other tenant: nothing at all
        CreatedTenant other = testTenants.create("001");
        TestJwts.Builder foreign = TestJwts.user(other.code()).username("viewer").roles("VIEWER").branchScope("ALL")
                .clearance("RESTRICTED");
        assertThat(total(foreign, "{\"query\":\"common\"}")).isZero();

        // defence in depth: the index says branch 001, the database now says 002 (no event): the hit is dropped
        jdbc.update("update document set branch_id = ? where id = ?", tenant.branch("002"), UUID.fromString(own));
        assertThat(ids(search(viewer, "{\"query\":\"common\"}"))).isEmpty();
    }

    @Test
    void permissionChangesApplyWithinTheStaleWindow() throws Exception {
        String id = upload(maker, invoice("Revocable report", "001", "CIF6", "OMR", null, null));
        await().atMost(INDEXED).until(() -> total(viewer, "{\"query\":\"revocable\"}") == 1);

        // a rule removed on another node (no event here): gone within the rule cache TTL, at most 60 s
        Instant removed = Instant.now();
        jdbc.update("delete from access_rule where tenant_id = ? and principal_id = 'VIEWER'", tenant.id());
        await().atMost(Duration.ofSeconds(60)).until(() -> total(viewer, "{\"query\":\"revocable\"}") == 0);
        assertThat(Duration.between(removed, Instant.now())).isLessThan(Duration.ofSeconds(60));

        // a grant: visible while valid, gone once expired
        TestJwts.Builder requester = user("requester", "MAKER", "002", "CONFIDENTIAL");
        api.as(requester, get("/api/v1/me")).andExpect(status().isOk());
        assertThat(total(requester, "{\"query\":\"revocable\"}")).isZero();
        UUID grant = inTenant(() -> grants.grant(UUID.fromString(id), UUID.fromString(requester.subject()),
                Set.of(Action.VIEW_METADATA), Instant.now().plus(1, ChronoUnit.DAYS), GrantSource.SHARE, null));
        assertThat(total(requester, "{\"query\":\"revocable\"}")).isEqualTo(1);
        jdbc.update("update document_grant set valid_until = now() - interval '1 second' where id = ?", grant);
        assertThat(total(requester, "{\"query\":\"revocable\"}")).isZero();

        // a change of the document's attributes (branch) is re-indexed after commit (viewers have no rule now)
        String etag = api.as(maker, get("/api/v1/documents/" + id)).andReturn().getResponse().getHeader("ETag");
        assertThat(total(maker2, "{\"query\":\"revocable\"}")).isZero();
        api.as(maker, patch("/api/v1/documents/" + id).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchCode\":\"002\"}"))
                .andExpect(status().isOk());
        await().atMost(INDEXED).until(() -> total(maker2, "{\"query\":\"revocable\"}") == 1);
    }

    @Test
    void theIndexCanBeRebuiltFromTheDatabase() throws Exception {
        String id = upload(maker, invoice("Rebuild candidate", "001", "CIF8", "OMR", null, null));
        processed(id, "archived content for the rebuild");
        await().atMost(INDEXED).until(() -> total(viewer, "{\"query\":\"archived\"}") == 1);
        HttpResponse<String> deleted = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                openSearchUrl + "/jdms-" + tenant.code().toLowerCase() + "-documents-*")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(total(viewer, "{\"query\":\"rebuild\"}")).isZero();

        TestJwts.Builder admin = user("admin", "TENANT_ADMIN", "ALL", "INTERNAL");
        api.as(viewer, post("/api/v1/admin/search/rebuild")).andExpect(status().isForbidden());
        api.as(admin, post("/api/v1/admin/search/rebuild")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty());

        await().atMost(INDEXED).until(() -> total(viewer, "{\"query\":\"archived\"}") == 1);
        assertThat(total(viewer, "{\"query\":\"rebuild candidate\"}")).isEqualTo(1);
    }

    /** Rebuilds load pages of documents in a few queries (B-13): same result as one by one, never another tenant's. */
    @Test
    void batchedLoadingMatchesSingleLoadingAndStaysInTheTenant() throws Exception {
        String withText = upload(maker, invoice("Batched one", "001", "CIF21", "OMR", null, null));
        processed(withText, "batched text content");
        String withoutText = upload(maker2, invoice("Batched two", "002", "CIF22", "OMR", null, null));
        CreatedTenant mine = tenant;
        tenant = testTenants.create("001");
        testMetadata.create(tenant);
        testMetadata.addSearchType(tenant);
        String otherTenants = upload(user("other", "MAKER", "001", "CONFIDENTIAL"),
                invoice("Batched three", "001", "CIF23", "OMR", null, null));
        tenant = mine;
        await().atMost(INDEXED).until(() -> total(viewer, "{\"query\":\"batched\"}") == 1);

        List<UUID> ids = List.of(UUID.fromString(withoutText), UUID.fromString(otherTenants), UUID.randomUUID(),
                UUID.fromString(withText));
        List<IndexableDocument> batch = inTenant(() -> indexable.findAll(ids));
        List<IndexableDocument> single = inTenant(() -> ids.stream().map(indexable::find).flatMap(Optional::stream)
                .toList());
        assertThat(batch).isEqualTo(single);
        assertThat(batch).extracting(document -> document.id().toString()).containsExactly(withoutText, withText);
        assertThat(batch.get(1).hasText()).isTrue();
        assertThat(inTenant(() -> indexable.currentTexts(batch, 1000)))
                .isEqualTo(Map.of(UUID.fromString(withText), "batched text content"));
    }

    @Test
    void invalidRequestsAreRefused() throws Exception {
        api.as(viewer, searchRequest("{\"size\":0}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("SIZE_INVALID"));
        api.as(viewer, searchRequest("{\"facets\":[\"cif\"]}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("FACET_UNKNOWN"));
        api.as(viewer, searchRequest("{\"page\":500,\"size\":100}")).andExpect(status().isUnprocessableContent());
        api.as(viewer, searchRequest("{\"query\":\"x\\\" OR \\\"*:*\"}")).andExpect(status().isOk()); // a value
        mvc.perform(searchRequest("{}")).andExpect(status().isUnauthorized());
    }

    // ---- helpers

    TestJwts.Builder user(String username, String role, String branch, String clearance) {
        return TestJwts.user(tenant.code()).username(username).roles(role).branchScope(branch).clearance(clearance);
    }

    static String invoice(String title, String branch, String cif, String currency, String note, String cardNo) {
        return "{\"documentTypeCode\":\"INVOICE\",\"title\":\"" + title + "\",\"branchCode\":\"" + branch
                + "\",\"cif\":\"" + cif + "\",\"accountNo\":\"ACC-" + cif + "\",\"customFields\":{\"currency\":\""
                + currency + "\"" + (note == null ? "" : ",\"note\":\"" + note + "\"")
                + (cardNo == null ? "" : ",\"card_no\":\"" + cardNo + "\"") + "}}";
    }

    String upload(TestJwts.Builder user, String metadata) throws Exception {
        return json(api.upload(user, metadata, randomBytes(200), "doc.pdf", key()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    /** What the worker reports when it has extracted the text (see ProcessingResultsIT). */
    void processed(String documentId, String text) throws Exception {
        String versionId = json(api.as(records, get("/api/v1/documents/" + documentId + "/versions")).andReturn()
                .getResponse().getContentAsString()).get(0).get("id").asString();
        StoredBlob blob = encryption.store(tenant.id(), tenant.code(),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), Long.MAX_VALUE);
        ProcessingResult result = new ProcessingResult(tenant.id(), UUID.fromString(documentId),
                UUID.fromString(versionId), ProcessingStep.RENDER, ProcessingOutcome.DONE, null, "application/pdf", 1,
                List.of(new RenditionBlob("TEXT", "text/plain; charset=UTF-8", blob.blobKey(), blob.sha256(),
                        blob.sizeBytes(), blob.wrappedKey(), blob.keyRef())), false, true);
        transactions.executeWithoutResult(status -> jobs.enqueue(new NewJob(tenant.id(),
                JobType.PROCESSING_RESULT.name(), jsonMapper.writeValueAsString(result)), 5));
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder searchRequest(String body) {
        return post("/api/v1/search").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    JsonNode search(TestJwts.Builder user, String body) throws Exception {
        ResultActions result = api.as(user, searchRequest(body)).andExpect(status().isOk());
        return json(result.andReturn().getResponse().getContentAsString());
    }

    int total(TestJwts.Builder user, String body) throws Exception {
        return search(user, body).get("total").asInt();
    }

    static List<String> ids(JsonNode result) {
        List<String> ids = new ArrayList<>();
        result.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    <T> T inTenant(Supplier<T> work) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> work.get()));
    }
}
