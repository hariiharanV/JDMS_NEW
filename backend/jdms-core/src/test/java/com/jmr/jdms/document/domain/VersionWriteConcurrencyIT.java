package com.jmr.jdms.document.domain;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Found by the B-08 UI journeys: a checker approved while the search indexer was marking the same version indexed;
 * the approval's update failed its optimistic check and the user got a 500. Every writer of a version now takes the
 * version's row lock, so concurrent writers wait for each other and both changes survive.
 */
@IntegrationTest
class VersionWriteConcurrencyIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired DocumentVersionRepository versions;
    @Autowired DocumentIndexSource indexSource;
    @Autowired TransactionTemplate transactions;

    @Test
    void anApprovalWhileTheVersionIsBeingMarkedIndexedWaitsAndSucceeds() throws Exception {
        DocumentsApi api = new DocumentsApi(mvc);
        CreatedTenant tenant = testTenants.create("001");
        testMetadata.create(tenant);
        TestJwts.Builder maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("RESTRICTED");
        TestJwts.Builder checker = TestJwts.user(tenant.code()).username("checker").roles("CHECKER").branchScope("001")
                .clearance("RESTRICTED");
        JsonNode created = json(api.upload(maker, "{\"documentTypeCode\":\"CONTRACT\",\"title\":\"Loan\","
                + "\"branchCode\":\"001\"}", randomBytes(100), "c.pdf", key()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        UUID documentId = UUID.fromString(created.get("id").asString());
        UUID versionId = UUID.fromString(created.at("/currentVersion/id").asString());
        String task = json(api.as(checker, get("/api/v1/tasks")).andReturn().getResponse().getContentAsString())
                .at("/items/0/id").asString();

        // the indexer holds the version (as when a search update lands at the same moment) for a while
        CountDownLatch locked = new CountDownLatch(1);
        Thread indexer = Thread.ofVirtual().start(() -> TenantScope.run(tenant.id(),
                () -> transactions.executeWithoutResult(status -> {
                    indexSource.markIndexed(documentId, versionId);
                    locked.countDown();
                    sleep(1500);
                })));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        api.as(checker, post("/api/v1/tasks/" + task + "/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}")).andExpect(status().isOk());
        indexer.join();

        DocumentVersion stored = TenantScope.call(tenant.id(), () -> transactions.execute(status ->
                versions.findByIdAndDocumentId(versionId, documentId).orElseThrow()));
        assertThat(stored.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(stored.getIndexStatus()).isEqualTo(IndexStatus.INDEXED);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
