package com.jmr.jdms.workflow;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * B-05 approval processes end to end through the API: single, multi-stage (DMN routing) and committee approvals;
 * approve, reject (comment required), return and resubmit; separation of duties enforced by the task service; admin
 * changes in the same inbox.
 */
@IntegrationTest
class ApprovalWorkflowIT {

    static final String CONTRACT = "{\"documentTypeCode\":\"CONTRACT\",\"title\":\"Contract\",\"branchCode\":\"001\"}";

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired TaskService flowableTasks;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder maker;
    TestJwts.Builder checker;
    TestJwts.Builder checker2;
    TestJwts.Builder otherBranchChecker;
    TestJwts.Builder admin1;
    TestJwts.Builder admin2;

    @BeforeEach
    void setUp() {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001", "002");
        testMetadata.create(tenant);
        testMetadata.addApprovalType(tenant, "LOAN_AGREEMENT", "multi-stage-approval");
        testMetadata.addApprovalType(tenant, "BOARD_RESOLUTION", "committee-approval");
        maker = user("maker", "MAKER", "001");
        checker = user("checker", "CHECKER", "001");
        checker2 = user("checker2", "CHECKER", "001");
        otherBranchChecker = user("checker-002", "CHECKER", "002");
        admin1 = user("admin1", "TENANT_ADMIN", "ALL");
        admin2 = user("admin2", "TENANT_ADMIN", "ALL");
    }

    @Nested
    class SingleApproval {

        @Test
        void checkerApprovesAndTheDocumentBecomesActive() throws Exception {
            String documentId = upload(maker, CONTRACT);
            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("PENDING_APPROVAL");
            assertThat(inbox(maker)).isEmpty();

            JsonNode task = only(inbox(checker));
            assertThat(task.get("kind").asString()).isEqualTo("APPROVAL");
            assertThat(task.get("processKey").asString()).isEqualTo("single-approval");
            assertThat(task.get("subjectType").asString()).isEqualTo("DOCUMENT");
            assertThat(task.get("documentId").asString()).isEqualTo(documentId);
            assertThat(task.get("makerId").asString()).isEqualTo(maker.subject());
            assertThat(task.get("summary").asString()).contains("Contract");
            assertThat(task.get("slaStatus").asString()).isEqualTo("ON_TIME");
            assertThat(task.get("dueAt").isNull()).isFalse();
            assertThat(texts(task.get("decisions"))).containsExactly("APPROVE", "REJECT", "RETURN");

            complete(checker, task.get("id").asString(), "APPROVE", null).andExpect(status().isOk());

            JsonNode approved = document(maker, documentId);
            assertThat(approved.get("status").asString()).isEqualTo("ACTIVE");
            assertThat(approved.at("/currentVersion/approvalStatus").asString()).isEqualTo("APPROVED");
            assertThat(inbox(checker)).isEmpty();
            assertThat(decisions(history(maker, documentId))).containsExactly("SUBMIT:maker", "APPROVE:checker");
        }

        @Test
        void rejectNeedsACommentAndRejectsTheDocument() throws Exception {
            String documentId = upload(maker, CONTRACT);
            String taskId = only(inbox(checker)).get("id").asString();

            complete(checker, taskId, "REJECT", " ").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("COMMENT_REQUIRED"));
            complete(checker, taskId, "REJECT", "Unsigned copy").andExpect(status().isOk());

            JsonNode rejected = document(maker, documentId);
            assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
            assertThat(rejected.at("/currentVersion/approvalStatus").asString()).isEqualTo("REJECTED");
            complete(checker, taskId, "APPROVE", null).andExpect(status().isNotFound()); // the process has ended
            JsonNode history = history(maker, documentId);
            assertThat(history.get(1).get("comment").asString()).isEqualTo("Unsigned copy");
            patchTitle(maker, documentId, "Changed").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_ACTIVE"));
        }

        @Test
        void returnedDocumentIsAmendedByTheMakerAndResubmitted() throws Exception {
            String documentId = upload(maker, CONTRACT);
            patchTitle(maker, documentId, "Too early").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DOCUMENT_PENDING_APPROVAL"));
            String taskId = only(inbox(checker)).get("id").asString();

            complete(checker, taskId, "RETURN", null).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("COMMENT_REQUIRED"));
            complete(checker, taskId, "RETURN", "Title must name the customer").andExpect(status().isOk());
            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("DRAFT");
            assertThat(inbox(checker)).isEmpty();

            JsonNode amend = only(inbox(maker));
            assertThat(amend.get("kind").asString()).isEqualTo("AMEND");
            assertThat(texts(amend.get("decisions"))).containsExactly("RESUBMIT");
            complete(maker, amend.get("id").asString(), "APPROVE", null).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("DECISION_NOT_ALLOWED"));
            complete(checker, amend.get("id").asString(), "RESUBMIT", null).andExpect(status().isNotFound());
            // amended directly (no separate metadata approval): the whole document is approved again
            patchTitle(maker, documentId, "Contract - ACME LLC").andExpect(status().isOk());
            complete(maker, amend.get("id").asString(), "RESUBMIT", "Customer added").andExpect(status().isOk());

            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("PENDING_APPROVAL");
            JsonNode second = only(inbox(checker));
            assertThat(second.get("round").asInt()).isEqualTo(2);
            complete(checker, second.get("id").asString(), "APPROVE", null).andExpect(status().isOk());

            JsonNode approved = document(maker, documentId);
            assertThat(approved.get("status").asString()).isEqualTo("ACTIVE");
            assertThat(approved.get("title").asString()).isEqualTo("Contract - ACME LLC");
            JsonNode history = history(maker, documentId);
            assertThat(decisions(history)).containsExactly("SUBMIT:maker", "RETURN:checker", "SUBMIT:maker",
                    "APPROVE:checker");
            assertThat(texts(history, "round")).containsExactly("1", "1", "2", "2");
        }

        @Test
        void theMakerCanNeverDecideTheirOwnUploadEvenWithTheCheckerRole() throws Exception {
            TestJwts.Builder makerChecker = TestJwts.user(tenant.code()).username("maker-checker")
                    .roles("MAKER", "CHECKER").branchScope("001").clearance("CONFIDENTIAL");
            upload(makerChecker, CONTRACT);
            assertThat(inbox(makerChecker)).isEmpty();
            String taskId = only(inbox(checker)).get("id").asString();

            complete(makerChecker, taskId, "APPROVE", null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TASK_MAKER_EXCLUDED"));
            api.as(makerChecker, post("/api/v1/tasks/" + taskId + "/claim")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TASK_MAKER_EXCLUDED"));
            // the maker may follow their item
            api.as(makerChecker, get("/api/v1/tasks/" + taskId)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.history[0].decision").value("SUBMIT"));
        }

        @Test
        void checkersOfOtherBranchesNeitherSeeNorDecide() throws Exception {
            upload(maker, CONTRACT);
            String taskId = only(inbox(checker)).get("id").asString();

            assertThat(inbox(otherBranchChecker)).isEmpty();
            api.as(otherBranchChecker, get("/api/v1/tasks/" + taskId)).andExpect(status().isNotFound());
            complete(otherBranchChecker, taskId, "APPROVE", null).andExpect(status().isNotFound());
            TestJwts.Builder viewer = user("viewer", "VIEWER", "001");
            assertThat(inbox(viewer)).isEmpty();
            complete(viewer, taskId, "APPROVE", null).andExpect(status().isNotFound());
        }

        @Test
        void aClaimedTaskBelongsToItsClaimant() throws Exception {
            upload(maker, CONTRACT);
            String taskId = only(inbox(checker)).get("id").asString();

            api.as(checker, post("/api/v1/tasks/" + taskId + "/claim")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.assignee").value(checker.subject()));
            assertThat(inbox(checker2)).isEmpty();
            complete(checker2, taskId, "APPROVE", null).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TASK_ALREADY_CLAIMED"));
            api.as(checker2, post("/api/v1/tasks/" + taskId + "/claim")).andExpect(status().isConflict());
            complete(checker, taskId, "APPROVE", null).andExpect(status().isOk());
        }

        @Test
        void overdueTasksAreFlagged() throws Exception {
            upload(maker, CONTRACT);
            String taskId = only(inbox(checker)).get("id").asString();
            flowableTasks.setDueDate(taskId, Date.from(Instant.now().minus(1, ChronoUnit.HOURS)));

            assertThat(only(inbox(checker)).get("slaStatus").asString()).isEqualTo("OVERDUE");
        }

        @Test
        void tasksOfAnotherTenantDoNotExist() throws Exception {
            upload(maker, CONTRACT);
            String taskId = only(inbox(checker)).get("id").asString();
            CreatedTenant other = testTenants.create("001");
            TestJwts.Builder foreignChecker = TestJwts.user(other.code()).username("checker").roles("CHECKER")
                    .branchScope("001").clearance("RESTRICTED");

            assertThat(inbox(foreignChecker)).isEmpty();
            api.as(foreignChecker, get("/api/v1/tasks/" + taskId)).andExpect(status().isNotFound());
            complete(foreignChecker, taskId, "APPROVE", null).andExpect(status().isNotFound());
            api.as(foreignChecker, get("/api/v1/tasks/not-a-task")).andExpect(status().isNotFound());
        }
    }

    @Nested
    class MultiStage {

        TestJwts.Builder committeeMember;

        @BeforeEach
        void setUp() {
            committeeMember = user("committee1", "COMMITTEE_MEMBER", "ALL");
        }

        @Test
        void smallLoansNeedOnlyTheBranchReview() throws Exception {
            String documentId = upload(maker, loan(50_000));
            JsonNode task = only(inbox(checker));
            assertThat(task.get("stageKey").asString()).isEqualTo("BRANCH_REVIEW");
            assertThat(task.get("processKey").asString()).isEqualTo("multi-stage-approval");

            complete(checker, task.get("id").asString(), "APPROVE", null).andExpect(status().isOk());

            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("ACTIVE");
            assertThat(inbox(committeeMember)).isEmpty();
        }

        @Test
        void largeLoansAlsoNeedTheCreditCommitteeStage() throws Exception {
            String documentId = upload(maker, loan(250_000));
            assertThat(inbox(committeeMember)).isEmpty(); // stages run in order
            complete(checker, only(inbox(checker)).get("id").asString(), "APPROVE", null)
                    .andExpect(status().isOk());
            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("PENDING_APPROVAL");

            JsonNode stage2 = only(inbox(committeeMember));
            assertThat(stage2.get("stageKey").asString()).isEqualTo("CREDIT_COMMITTEE");
            assertThat(stage2.get("name").asString()).isEqualTo("Credit committee");
            assertThat(inbox(checker)).isEmpty();
            complete(committeeMember, stage2.get("id").asString(), "APPROVE", null).andExpect(status().isOk());

            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("ACTIVE");
            JsonNode history = history(maker, documentId);
            assertThat(decisions(history)).containsExactly("SUBMIT:maker", "APPROVE:checker",
                    "APPROVE:committee1");
            assertThat(texts(history, "stageKey")).containsExactly("", "BRANCH_REVIEW", "CREDIT_COMMITTEE");
        }

        @Test
        void oneUserCannotApproveTwoStagesOfTheSameItem() throws Exception {
            TestJwts.Builder both = TestJwts.user(tenant.code()).username("both").roles("CHECKER", "COMMITTEE_MEMBER")
                    .branchScope("001").clearance("CONFIDENTIAL");
            String documentId = upload(maker, loan(250_000));
            complete(both, only(inbox(both)).get("id").asString(), "APPROVE", null).andExpect(status().isOk());

            assertThat(inbox(both)).isEmpty();
            String stage2 = only(inbox(committeeMember)).get("id").asString();
            complete(both, stage2, "APPROVE", null).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TASK_ALREADY_DECIDED"));
            complete(committeeMember, stage2, "REJECT", "Collateral insufficient").andExpect(status().isOk());

            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("REJECTED");
        }

        @Test
        void resubmissionIsRoutedAgainWithTheAmendedAmount() throws Exception {
            String documentId = upload(maker, loan(250_000));
            complete(checker, only(inbox(checker)).get("id").asString(), "APPROVE", null)
                    .andExpect(status().isOk());
            complete(committeeMember, only(inbox(committeeMember)).get("id").asString(), "RETURN",
                    "Split the facility").andExpect(status().isOk());

            api.as(maker, patch("/api/v1/documents/" + documentId).header("If-Match", etag(maker, documentId))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"customFields\":{\"amount\":90000}}"))
                    .andExpect(status().isOk());
            complete(maker, only(inbox(maker)).get("id").asString(), "RESUBMIT", null).andExpect(status().isOk());
            complete(checker, only(inbox(checker)).get("id").asString(), "APPROVE", null)
                    .andExpect(status().isOk());

            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("ACTIVE");
            assertThat(inbox(committeeMember)).isEmpty();
        }
    }

    @Nested
    class Committee {

        TestJwts.Builder member1;
        TestJwts.Builder member2;
        TestJwts.Builder member3;

        @BeforeEach
        void setUp() throws Exception {
            member1 = user("member1", "COMMITTEE_MEMBER", "ALL");
            member2 = user("member2", "COMMITTEE_MEMBER", "ALL");
            member3 = user("member3", "COMMITTEE_MEMBER", "ALL");
        }

        @Test
        void approvedWhenTheQuorumIsReachedAndOpenVotesAreWithdrawn() throws Exception {
            createCommittee(2, member1, member2, member3);
            String documentId = upload(maker, resolution());
            String vote1 = only(inbox(member1)).get("id").asString();
            String vote2 = only(inbox(member2)).get("id").asString();
            JsonNode vote3 = only(inbox(member3));
            assertThat(vote3.get("kind").asString()).isEqualTo("VOTE");
            assertThat(texts(vote3.get("decisions"))).containsExactly("APPROVE", "REJECT");
            assertThat(inbox(checker)).isEmpty();

            complete(member1, vote1, "RETURN", "x").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("DECISION_NOT_ALLOWED"));
            complete(member1, vote2, "APPROVE", null).andExpect(status().isNotFound()); // not their vote
            complete(member1, vote1, "APPROVE", null).andExpect(status().isOk());
            complete(member1, vote1, "APPROVE", null).andExpect(status().isNotFound()); // no second vote
            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("PENDING_APPROVAL");

            complete(member2, vote2, "APPROVE", "Agreed").andExpect(status().isOk());

            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("ACTIVE");
            assertThat(inbox(member3)).isEmpty();
            complete(member3, vote3.get("id").asString(), "REJECT", "late").andExpect(status().isNotFound());
            assertThat(decisions(history(maker, documentId))).containsExactly("SUBMIT:maker",
                    "VOTE_APPROVE:member1", "VOTE_APPROVE:member2");
        }

        @Test
        void rejectedAsSoonAsTheQuorumCanNoLongerBeReached() throws Exception {
            createCommittee(2, member1, member2, member3);
            String documentId = upload(maker, resolution());

            complete(member1, only(inbox(member1)).get("id").asString(), "REJECT", null)
                    .andExpect(status().isUnprocessableContent());
            complete(member1, only(inbox(member1)).get("id").asString(), "REJECT", "No").andExpect(status().isOk());
            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("PENDING_APPROVAL");
            complete(member3, only(inbox(member3)).get("id").asString(), "REJECT", "No").andExpect(status().isOk());

            // 2 of 3 rejected: at most 1 approval remains possible, below the quorum of 2
            assertThat(document(maker, documentId).get("status").asString()).isEqualTo("REJECTED");
            assertThat(inbox(member2)).isEmpty();
        }

        @Test
        void theMakerNeverVotesOnTheirOwnItem() throws Exception {
            TestJwts.Builder makerMember = TestJwts.user(tenant.code()).username("maker-member")
                    .roles("MAKER", "COMMITTEE_MEMBER").branchScope("001").clearance("CONFIDENTIAL");
            createCommittee(2, makerMember, member1, member2);

            upload(makerMember, resolution());

            assertThat(inbox(makerMember)).isEmpty();
            assertThat(inbox(member1)).hasSize(1);
            assertThat(inbox(member2)).hasSize(1);
        }

        @Test
        void anItemTheCommitteeCouldNeverApproveIsNotAccepted() throws Exception {
            TestJwts.Builder makerMember = TestJwts.user(tenant.code()).username("maker-member")
                    .roles("MAKER", "COMMITTEE_MEMBER").branchScope("001").clearance("CONFIDENTIAL");
            createCommittee(3, makerMember, member1, member2);

            api.upload(makerMember, resolution(), randomBytes(100), "r.pdf", key())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COMMITTEE_QUORUM_UNREACHABLE"));

            api.as(makerMember, get("/api/v1/documents")).andExpect(jsonPath("$.total").value(0));
        }

        @Test
        void withoutACommitteeTheUploadIsRefused() throws Exception {
            api.upload(maker, resolution(), randomBytes(100), "r.pdf", key()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("APPROVAL_ROUTING_FAILED"));
        }

        void createCommittee(int quorum, TestJwts.Builder... members) throws Exception {
            List<String> entries = new ArrayList<>();
            for (TestJwts.Builder member : members) {
                api.as(member, get("/api/v1/me")).andExpect(status().isOk());
                entries.add("{\"userId\":\"" + member.subject() + "\",\"designation\":\"Member\"}");
            }
            String body = "{\"code\":\"CREDIT_COMMITTEE\",\"name\":\"Credit committee\",\"minQuorum\":" + quorum
                    + ",\"members\":[" + String.join(",", entries) + "]}";
            api.as(admin1, post("/api/v1/admin/committees").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted());
            complete(admin2, only(inbox(admin2)).get("id").asString(), "APPROVE", null).andExpect(status().isOk());
            api.as(admin1, get("/api/v1/admin/committees")).andExpect(jsonPath("$.items[0].members.length()")
                    .value(members.length));
        }
    }

    @Nested
    class AdminChanges {

        @Test
        void pendingChangesAreTasksForTheApproverRole() throws Exception {
            String changeId = json(api.as(admin1, post("/api/v1/admin/cabinets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"LOANS\",\"name\":\"Loans\",\"maxFileSizeMb\":10,\"quotaMb\":100}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                    .get("id").asString();

            assertThat(inbox(admin1)).isEmpty(); // the proposer
            assertThat(inbox(checker)).isEmpty(); // not their role
            JsonNode task = only(inbox(admin2));
            assertThat(task.get("subjectType").asString()).isEqualTo("ADMIN_CHANGE");
            assertThat(task.get("subjectId").asString()).isEqualTo(changeId);
            assertThat(texts(task.get("decisions"))).containsExactly("APPROVE", "REJECT");

            complete(admin2, task.get("id").asString(), "RETURN", "x").andExpect(status().isUnprocessableContent());
            complete(admin2, task.get("id").asString(), "APPROVE", "ok").andExpect(status().isOk())
                    .andExpect(jsonPath("$.pendingChangeId").value(changeId));

            api.as(admin1, get("/api/v1/admin/pending-changes/" + changeId))
                    .andExpect(jsonPath("$.status").value("APPROVED"));
            api.as(admin1, get("/api/v1/admin/cabinets")).andExpect(jsonPath("$.items[*].code",
                    org.hamcrest.Matchers.hasItem("LOANS")));
        }

        @Test
        void decidingThePendingChangeDirectlyClosesItsTask() throws Exception {
            String changeId = json(api.as(admin1, post("/api/v1/admin/cabinets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"CARDS\",\"name\":\"Cards\",\"maxFileSizeMb\":10,\"quotaMb\":100}"))
                    .andReturn().getResponse().getContentAsString()).get("id").asString();
            assertThat(inbox(admin2)).hasSize(1);

            api.as(admin2, post("/api/v1/admin/pending-changes/" + changeId + "/reject")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Not needed\"}"))
                    .andExpect(status().isOk());

            assertThat(inbox(admin2)).isEmpty();
        }

        @Test
        void metadataChangesOfDocumentsGoToCheckersAndShowInTheDocumentHistory() throws Exception {
            String documentId = upload(maker, CONTRACT);
            complete(checker, only(inbox(checker)).get("id").asString(), "APPROVE", null).andExpect(status().isOk());

            patchTitle(maker, documentId, "Contract (signed)").andExpect(status().isAccepted());
            JsonNode task = only(inbox(checker));
            assertThat(task.get("subjectType").asString()).isEqualTo("METADATA_CHANGE");
            assertThat(task.get("documentId").asString()).isEqualTo(documentId);
            complete(checker, task.get("id").asString(), "APPROVE", null).andExpect(status().isOk());

            assertThat(document(maker, documentId).get("title").asString()).isEqualTo("Contract (signed)");
            JsonNode history = history(maker, documentId);
            assertThat(decisions(history)).containsExactly("SUBMIT:maker", "APPROVE:checker", "SUBMIT:maker",
                    "APPROVE:checker");
            assertThat(texts(history, "subjectType")).containsExactly("DOCUMENT", "DOCUMENT", "METADATA_CHANGE",
                    "METADATA_CHANGE");
        }
    }

    @Test
    void historyIsVisibleOnlyToThoseWhoMaySeeTheDocument() throws Exception {
        String documentId = upload(maker, CONTRACT);
        api.as(otherBranchChecker, get("/api/v1/documents/" + documentId + "/history"))
                .andExpect(status().isNotFound());
        api.as(checker, get("/api/v1/documents/" + documentId + "/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].decision").value(contains("SUBMIT")));
    }

    // ---- helpers

    TestJwts.Builder user(String username, String role, String branch) {
        return TestJwts.user(tenant.code()).username(username).roles(role).branchScope(branch)
                .clearance("CONFIDENTIAL");
    }

    static String loan(int amount) {
        return "{\"documentTypeCode\":\"LOAN_AGREEMENT\",\"title\":\"Loan\",\"branchCode\":\"001\","
                + "\"customFields\":{\"amount\":" + amount + "}}";
    }

    static String resolution() {
        return "{\"documentTypeCode\":\"BOARD_RESOLUTION\",\"title\":\"Resolution\",\"branchCode\":\"001\"}";
    }

    String upload(TestJwts.Builder user, String metadata) throws Exception {
        return json(api.upload(user, metadata, randomBytes(100), "doc.pdf", key()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    List<JsonNode> inbox(TestJwts.Builder user) throws Exception {
        JsonNode body = json(api.as(user, get("/api/v1/tasks")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertThat(body.get("total").asInt()).isEqualTo(body.get("items").size());
        return StreamSupport.stream(body.get("items").spliterator(), false).toList();
    }

    static JsonNode only(List<JsonNode> tasks) {
        assertThat(tasks).hasSize(1);
        return tasks.getFirst();
    }

    ResultActions complete(TestJwts.Builder user, String taskId, String decision, String comment) throws Exception {
        String body = "{\"decision\":\"" + decision + "\"" + (comment == null ? "" : ",\"comment\":\"" + comment
                + "\"") + "}";
        return api.as(user, post("/api/v1/tasks/" + taskId + "/complete").contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    JsonNode document(TestJwts.Builder user, String documentId) throws Exception {
        return json(api.as(user, get("/api/v1/documents/" + documentId)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    JsonNode history(TestJwts.Builder user, String documentId) throws Exception {
        return json(api.as(user, get("/api/v1/documents/" + documentId + "/history")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    String etag(TestJwts.Builder user, String documentId) throws Exception {
        return api.as(user, get("/api/v1/documents/" + documentId)).andReturn().getResponse().getHeader("ETag");
    }

    ResultActions patchTitle(TestJwts.Builder user, String documentId, String title) throws Exception {
        return api.as(user, patch("/api/v1/documents/" + documentId).header("If-Match", etag(user, documentId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"));
    }

    static List<String> decisions(JsonNode history) {
        return StreamSupport.stream(history.spliterator(), false)
                .map(entry -> entry.get("decision").asString() + ":" + entry.get("actorName").asString())
                .collect(Collectors.toList());
    }

    static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
    }

    static List<String> texts(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(entry -> entry.get(field).isNull() ? "" : entry.get(field).asString()).toList();
    }
}
