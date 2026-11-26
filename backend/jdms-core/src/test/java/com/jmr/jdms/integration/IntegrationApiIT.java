package com.jmr.jdms.integration;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.TestcontainersConfiguration.SeaweedFsContainer;
import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.MultipartBody;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;

/**
 * B-09 integration API with a mock FLEXCUBE caller (OAuth2 client credentials): upload by business keys, list,
 * metadata, download, preview, new version; problem+json errors; idempotency; client identity only; rate limiting;
 * audit per call; object store and search outages.
 */
@IntegrationTest
class IntegrationApiIT {

    static final String BASE = "/integration/v1/documents";

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired JdbcTemplate jdbc;
    @Autowired SeaweedFsContainer seaweedfs;
    @Autowired @Qualifier("opensearch") GenericContainer<?> opensearch;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder flexcube;
    TestMetadata.Model model;

    @BeforeEach
    void setUp() {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001", "002");
        model = testMetadata.create(tenant);
        flexcube = TestJwts.client(tenant.code(), "fcubs").roles("INTEGRATION_CLIENT").branchScope("ALL")
                .clearance("CONFIDENTIAL");
    }

    @Test
    void flexcubeUploadsByBusinessKeysListsAndDownloads() throws Exception {
        byte[] file = randomBytes(3000);
        JsonNode created = json(upload(flexcube, receipt("CUST100", "ACC-1", "LN-77"), file, key())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(BASE + "/")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString());
        String id = created.get("documentId").asString();

        for (String query : List.of("cif=CUST100", "accountNo=ACC-1", "contractRef=LN-77",
                "cif=CUST100&documentTypeCode=RECEIPT")) {
            api.as(flexcube, get(BASE + "?" + query)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(id));
        }
        api.as(flexcube, get(BASE + "?cif=OTHER")).andExpect(jsonPath("$.total").value(0));
        api.as(flexcube, get(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.cif").value("CUST100")) // customerNo is the CIF
                .andExpect(jsonPath("$.title").value("RECEIPT LN-77"))
                .andExpect(jsonPath("$.sourceSystem").value("INTEGRATION:fcubs:FCUBS"));
        api.as(flexcube, get(BASE + "/" + id + "/content")).andExpect(status().isOk())
                .andExpect(content().bytes(file));
        api.as(flexcube, get(BASE + "/" + id + "/preview")).andExpect(status().isConflict()) // not processed yet
                .andExpect(jsonPath("$.code").value("PREVIEW_UNAVAILABLE"));

        byte[] second = randomBytes(1000);
        String versionKey = key();
        newVersion(flexcube, id, second, versionKey).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("2.0"));
        newVersion(flexcube, id, second, versionKey).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("2.0")); // replayed, not a third version
        api.as(flexcube, get(BASE + "/" + id + "/content")).andExpect(content().bytes(second));
    }

    @Test
    void aCustomerCreationScreenFilesIntoANamedFolderAndFindsTheDocumentByItsOwnReference() throws Exception {
        byte[] file = randomBytes(900);
        String metadata = "{\"externalSystem\":\"FCUBS\",\"documentTypeCode\":\"RECEIPT\",\"branchCode\":\"001\","
                + "\"cif\":\"C7001\",\"customerName\":\"Ahmed Al Balushi\",\"externalRef\":\"STDCIF-001-2026-0042\","
                + "\"folderPath\":\"/Inbox/\",\"customFields\":{\"amount\":\"10\"}}";
        JsonNode created = json(upload(flexcube, metadata, file, key()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String documentId = created.get("documentId").asString();

        // FLEXCUBE stores the DMS reference; the document sits in the folder named by its path
        api.as(flexcube, get(BASE + "/" + documentId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value(model.inboxFolderId().toString()))
                .andExpect(jsonPath("$.externalRef").value("STDCIF-001-2026-0042"))
                .andExpect(jsonPath("$.sourceSystem").value("INTEGRATION:fcubs:FCUBS"));
        // later, from its own reference alone
        api.as(flexcube, get(BASE).param("externalRef", "STDCIF-001-2026-0042")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(documentId));
        api.as(flexcube, get(BASE + "/" + documentId + "/content")).andExpect(status().isOk())
                .andExpect(content().bytes(file));

        // a path that does not exist in the type's cabinet is refused before anything is stored
        upload(flexcube, metadata.replace("/Inbox/", "/Nowhere"), randomBytes(10), key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("folderPath"))
                .andExpect(jsonPath("$.errors[0].code").value("FOLDER_INVALID"));
        api.as(flexcube, get(BASE).param("externalRef", "NO-SUCH-REF")).andExpect(jsonPath("$.total").value(0));
        // another tenant's client finds nothing by the same reference
        CreatedTenant other = testTenants.create("001");
        TestJwts.Builder foreign = TestJwts.client(other.code(), "fcubs").roles("INTEGRATION_CLIENT").branchScope("ALL")
                .clearance("RESTRICTED");
        api.as(foreign, get(BASE).param("externalRef", "STDCIF-001-2026-0042")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void clientsDeleteOnlyWithTheDeletePermission() throws Exception {
        String id = json(upload(flexcube, receipt("CUST300", "ACC-3", "LN-3"), randomBytes(100), key())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("documentId").asString();
        api.as(flexcube, delete(BASE + "/" + id)).andExpect(status().isForbidden());

        // a bank system the bank allowed to delete its branch's documents (maker permissions)
        TestJwts.Builder cleanup = TestJwts.client(tenant.code(), "lms").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        api.as(cleanup, delete(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
    }

    @Test
    void errorsAreProblemJson() throws Exception {
        upload(flexcube, receipt("C1", "A1", "L1").replace("RECEIPT", "NO_SUCH_TYPE"), randomBytes(10), key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("documentTypeCode"));
        upload(flexcube, receipt("C1", "A1", "L1").replace("\"customFields\":{\"amount\":125}", "\"customFields\":{}"),
                randomBytes(10), key()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("customFields.amount"));
        upload(flexcube, receipt("C1", "A1", "L1").replace("\"externalSystem\":\"FCUBS\",", ""), randomBytes(10),
                key()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("EXTERNAL_SYSTEM_INVALID"));
        upload(flexcube, receipt("C1", "A1", "L1"), randomBytes(10), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        api.as(flexcube, get(BASE)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("BUSINESS_KEY_REQUIRED"));
        api.as(flexcube, get(BASE + "/00000000-0000-7000-8000-000000000000")).andExpect(status().isNotFound());
    }

    @Test
    void theClientIdentityAloneDecidesPermissions() throws Exception {
        TestJwts.Builder readOnly = TestJwts.client(tenant.code(), "crm").roles("VIEWER").branchScope("ALL")
                .clearance("CONFIDENTIAL");
        TestJwts.Builder maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        api.as(maker, get("/api/v1/me")).andExpect(status().isOk());

        upload(readOnly, receipt("C2", "A2", "L2"), randomBytes(10), key()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        // naming a user (as legacy callers did) is refused, never trusted
        String impersonating = receipt("C2", "A2", "L2").replace("{\"externalSystem\"",
                "{\"user_id\":\"" + maker.subject() + "\",\"role_id\":\"MAKER\",\"externalSystem\"");
        upload(readOnly, impersonating, randomBytes(10), key()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("FIELD_NOT_ALLOWED"))));
        upload(flexcube, impersonating, randomBytes(10), key()).andExpect(status().isUnprocessableContent());
        // people use the main API, not the integration API
        api.as(maker, get(BASE + "?cif=C2")).andExpect(status().isForbidden());
    }

    @Test
    void replayedUploadsReturnTheOriginalAndReusedKeysAreRefused() throws Exception {
        String idempotencyKey = key();
        byte[] file = randomBytes(500);
        String first = json(upload(flexcube, receipt("C3", "A3", "L3"), file, idempotencyKey)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("documentId").asString();

        upload(flexcube, receipt("C3", "A3", "L3"), file, idempotencyKey).andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").value(first));
        upload(flexcube, receipt("C3", "A3", "L4"), file, idempotencyKey).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        api.as(flexcube, get(BASE + "?cif=C3")).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void clientsAreRateLimitedAndEveryCallIsAudited() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        ResultActions limited = null;
        for (int i = 0; i < 80; i++) {
            ResultActions result = api.as(flexcube, get(BASE + "?cif=C9"));
            int status = result.andReturn().getResponse().getStatus();
            statuses.add(status);
            if (status == 429 && limited == null) {
                limited = result;
            }
        }
        assertThat(statuses).contains(200, 429);
        limited.andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        Integer audited = jdbc.queryForObject("select count(*) from audit_event where tenant_id = ? and action = "
                + "'INTEGRATION_CALL' and details ->> 'clientId' = 'service-account-fcubs'", Integer.class,
                tenant.id());
        assertThat(audited).isEqualTo(statuses.stream().filter(status -> status == 200).count());
        assertThat(jdbc.queryForObject("select details ->> 'route' from audit_event where tenant_id = ? and action "
                + "= 'INTEGRATION_CALL' limit 1", String.class, tenant.id())).isEqualTo(BASE);
    }

    @Test
    void anObjectStoreOutageIsRetryableAndASearchOutageDoesNotStopUploads() throws Exception {
        var docker = DockerClientFactory.instance().client();
        String idempotencyKey = key();
        byte[] file = randomBytes(800);
        docker.pauseContainerCmd(seaweedfs.getContainerId()).exec();
        try {
            upload(flexcube, receipt("C5", "A5", "L5"), file, idempotencyKey)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        } finally {
            docker.unpauseContainerCmd(seaweedfs.getContainerId()).exec();
        }
        upload(flexcube, receipt("C5", "A5", "L5"), file, idempotencyKey).andExpect(status().isCreated());

        docker.pauseContainerCmd(opensearch.getContainerId()).exec();
        String id;
        try {
            id = json(upload(flexcube, receipt("C6", "A6", "L6"), randomBytes(800), key())
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                    .get("documentId").asString();
            api.as(flexcube, post("/api/v1/search").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"filters\":{\"cif\":\"C6\"}}")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
        } finally {
            docker.unpauseContainerCmd(opensearch.getContainerId()).exec();
        }
        // indexing is retried (INDEX job) once search is back
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                api.as(flexcube, post("/api/v1/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"cif\":\"C6\"}}")).andExpect(jsonPath("$.items[0].id").value(id)));
    }

    // ---- helpers

    static String receipt(String customerNo, String accountNo, String contractRef) {
        return "{\"externalSystem\":\"FCUBS\",\"documentTypeCode\":\"RECEIPT\",\"branchCode\":\"001\","
                + "\"customerNo\":\"" + customerNo + "\",\"accountNo\":\"" + accountNo + "\",\"contractRef\":\""
                + contractRef + "\",\"issueDate\":\"2026-01-15\",\"customFields\":{\"amount\":125}}";
    }

    ResultActions upload(TestJwts.Builder client, String metadata, byte[] file, String idempotencyKey)
            throws Exception {
        MultipartBody body = MultipartBody.create().json("metadata", metadata)
                .file("file", "scan.pdf", "application/pdf", file);
        MockHttpServletRequestBuilder request = post(BASE).contentType(body.contentType()).content(body.bytes());
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return api.as(client, request);
    }

    ResultActions newVersion(TestJwts.Builder client, String id, byte[] file, String idempotencyKey)
            throws Exception {
        MultipartBody body = MultipartBody.create().field("comment", "Signed copy", null)
                .file("file", "signed.pdf", "application/pdf", file);
        return api.as(client, post(BASE + "/" + id + "/versions").header("Idempotency-Key", idempotencyKey)
                .contentType(body.contentType()).content(body.bytes()));
    }
}
