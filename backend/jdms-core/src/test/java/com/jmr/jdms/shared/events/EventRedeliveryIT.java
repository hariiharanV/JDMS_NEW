package com.jmr.jdms.shared.events;

import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jmr.jdms.TestcontainersConfiguration.OwnerJdbc;
import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * B-13: an outbox delivery whose listener failed after the upload committed (here: the SCAN job it queued is gone and
 * the publication is marked failed, as the staleness monitor does) is delivered again, so the version is processed.
 */
@IntegrationTest
class EventRedeliveryIT {

    static final String SCHEDULER = "com.jmr.jdms.document.domain.ProcessingScheduler.on("
            + "com.jmr.jdms.contracts.VersionCreated)";

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired OwnerJdbc owner;
    @Autowired EventRedelivery redelivery;

    @Test
    void aFailedDeliveryIsDeliveredAgain() throws Exception {
        CreatedTenant tenant = testTenants.create("001");
        testMetadata.create(tenant);
        TestJwts.Builder maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        String versionId = new DocumentsApi(mvc).uploadReceipt(maker, randomBytes(300), "C1")
                .at("/currentVersion/id").asString();
        await().atMost(Duration.ofSeconds(20)).until(() -> scanJobs(versionId) == 1);

        // the listener "failed": its job never made it, its publication is failed and incomplete
        owner.jdbc().update("delete from job where type = 'SCAN' and payload ->> 'versionId' = ?", versionId);
        int failed = owner.jdbc().update("""
                update event_publication set status = 'FAILED', completion_date = null
                 where listener_id = ? and serialized_event like ?
                """, SCHEDULER, "%" + versionId + "%");
        assertThat(failed).isEqualTo(1);

        redelivery.redeliverFailed();

        await().atMost(Duration.ofSeconds(20)).until(() -> scanJobs(versionId) == 1);
        await().atMost(Duration.ofSeconds(20)).until(() -> owner.jdbc().queryForObject("""
                select count(*) from event_publication
                 where listener_id = ? and serialized_event like ? and completion_date is null
                """, Integer.class, SCHEDULER, "%" + versionId + "%") == 0);
    }

    private int scanJobs(String versionId) {
        return owner.jdbc().queryForObject("select count(*) from job where type = 'SCAN' and payload ->> 'versionId' = ?",
                Integer.class, versionId);
    }
}
