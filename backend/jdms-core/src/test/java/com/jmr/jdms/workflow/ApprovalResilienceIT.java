package com.jmr.jdms.workflow;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * B-05 failure test (spec §4.4): the database connection dies in the middle of completing an approval, after the
 * approval record was written and while the process moves on. Nothing of the attempt survives; the process resumes
 * from its last committed state and the retried decision is recorded exactly once.
 */
@IntegrationTest
class ApprovalResilienceIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired RuntimeService runtime;
    @Autowired DataSource dataSource;
    @Autowired PostgreSQLContainer postgres;

    final OutageOnTaskCompletion outage = new OutageOnTaskCompletion();
    DocumentsApi api;
    TestJwts.Builder maker;
    TestJwts.Builder checker;
    TestJwts.Builder committeeMember;

    @BeforeEach
    void setUp() {
        api = new DocumentsApi(mvc);
        CreatedTenant tenant = testTenants.create("001");
        testMetadata.create(tenant);
        testMetadata.addApprovalType(tenant, "LOAN_AGREEMENT", "multi-stage-approval");
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        checker = TestJwts.user(tenant.code()).username("checker").roles("CHECKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        committeeMember = TestJwts.user(tenant.code()).username("committee").roles("COMMITTEE_MEMBER")
                .branchScope("ALL").clearance("CONFIDENTIAL");
        runtime.addEventListener(outage, FlowableEngineEventType.TASK_COMPLETED);
    }

    @AfterEach
    void tearDown() {
        runtime.removeEventListener(outage);
    }

    @Test
    void databaseOutageMidCompletionResumesWithoutDuplicateApprovals() throws Exception {
        String documentId = json(api.upload(maker, "{\"documentTypeCode\":\"LOAN_AGREEMENT\",\"title\":\"Loan\","
                + "\"branchCode\":\"001\",\"customFields\":{\"amount\":500000}}", randomBytes(100), "l.pdf", key())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        complete(checker, onlyTask(checker), "APPROVE").andExpect(status().isOk()); // stage 1 committed
        String stage2 = onlyTask(committeeMember);

        outage.arm();
        complete(committeeMember, stage2, "APPROVE").andExpect(status().is5xxServerError());
        assertThat(outage.fired()).isTrue();

        // last committed state: stage 1 approved, stage 2 open with the same task, no trace of the failed attempt
        assertThat(onlyTask(committeeMember)).isEqualTo(stage2);
        assertThat(documentStatus(documentId)).isEqualTo("PENDING_APPROVAL");
        assertThat(decisions(documentId)).containsExactly("SUBMIT", "APPROVE");

        complete(committeeMember, stage2, "APPROVE").andExpect(status().isOk());
        complete(committeeMember, stage2, "APPROVE").andExpect(status().isNotFound()); // a retry after success

        assertThat(documentStatus(documentId)).isEqualTo("ACTIVE");
        assertThat(decisions(documentId)).containsExactly("SUBMIT", "APPROVE", "APPROVE");
    }

    /**
     * When armed, kills the database session of the transaction that is completing a task (as an outage would),
     * from a separate connection. The statements that follow in that transaction fail.
     */
    class OutageOnTaskCompletion implements FlowableEventListener {

        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicBoolean fired = new AtomicBoolean();

        void arm() {
            armed.set(true);
        }

        boolean fired() {
            return fired.get();
        }

        @Override
        public void onEvent(FlowableEvent event) {
            if (!armed.compareAndSet(true, false)) {
                return;
            }
            try {
                Connection transactional = DataSourceUtils.getConnection(dataSource);
                int pid;
                try (Statement statement = transactional.createStatement();
                        ResultSet result = statement.executeQuery("select pg_backend_pid()")) {
                    result.next();
                    pid = result.getInt(1);
                }
                try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                        postgres.getPassword()); Statement kill = admin.createStatement()) {
                    kill.execute("select pg_terminate_backend(" + pid + ")");
                }
                fired.set(true);
            } catch (Exception e) {
                throw new IllegalStateException("Could not simulate the outage", e);
            }
        }

        @Override
        public boolean isFailOnException() {
            return true;
        }

        @Override
        public boolean isFireOnTransactionLifecycleEvent() {
            return false;
        }

        @Override
        public String getOnTransaction() {
            return null;
        }
    }

    String onlyTask(TestJwts.Builder user) throws Exception {
        JsonNode items = json(api.as(user, get("/api/v1/tasks")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("items");
        assertThat(items.size()).isEqualTo(1);
        return items.get(0).get("id").asString();
    }

    ResultActions complete(TestJwts.Builder user, String taskId, String decision) throws Exception {
        return api.as(user, post("/api/v1/tasks/" + taskId + "/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\"}"));
    }

    String documentStatus(String documentId) throws Exception {
        return json(api.as(maker, get("/api/v1/documents/" + documentId)).andReturn().getResponse()
                .getContentAsString()).get("status").asString();
    }

    List<String> decisions(String documentId) throws Exception {
        JsonNode history = json(api.as(maker, get("/api/v1/documents/" + documentId + "/history")).andReturn()
                .getResponse().getContentAsString());
        return StreamSupport.stream(history.spliterator(), false).map(entry -> entry.get("decision").asString())
                .toList();
    }
}
