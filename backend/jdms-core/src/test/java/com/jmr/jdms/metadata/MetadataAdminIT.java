package com.jmr.jdms.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.audit.domain.AuditEvent;
import com.jmr.jdms.audit.domain.AuditEventRepository;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-02: metadata administration through the API with maker-checker. Every write is a proposal (202) that only a
 * second tenant admin can approve; the proposer cannot.
 */
@IntegrationTest
class MetadataAdminIT {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TransactionTemplate transactions;
    @Autowired AuditEventRepository auditEvents;

    CreatedTenant tenant;
    TestJwts.Builder admin1;
    TestJwts.Builder admin2;
    TestJwts.Builder maker;
    TestJwts.Builder securityAdmin;
    TestJwts.Builder otherTenantAdmin;

    @BeforeEach
    void users() {
        tenant = testTenants.create("001");
        admin1 = TestJwts.user(tenant.code()).username("tadmin1").roles("TENANT_ADMIN").branchScope("ALL");
        admin2 = TestJwts.user(tenant.code()).username("tadmin2").roles("TENANT_ADMIN").branchScope("ALL");
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001");
        securityAdmin = TestJwts.user(tenant.code()).username("secadmin").roles("SECURITY_ADMIN").branchScope("ALL");
        otherTenantAdmin = TestJwts.user(testTenants.create("001").code()).roles("TENANT_ADMIN").branchScope("ALL");
    }

    // ---------------------------------------------------------------- maker-checker lifecycle

    @Test
    void cabinetIsCreatedOnlyAfterASecondAdminApproves() throws Exception {
        MvcResult proposed = as(admin1, post("/api/v1/admin/cabinets").contentType(MediaType.APPLICATION_JSON)
                .content(cabinet("CUSTOMERS", "Customers", 100, 1000)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/admin/pending-changes/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.subjectType").value("CABINET"))
                .andExpect(jsonPath("$.operation").value("CREATE"))
                .andExpect(jsonPath("$.summary").value("Create cabinet CUSTOMERS"))
                .andReturn();
        String changeId = id(proposed);
        String cabinetId = json(proposed).get("subjectId").asString();

        as(admin1, get("/api/v1/admin/cabinets")).andExpect(jsonPath("$.total").value(0));
        as(admin1, get("/api/v1/admin/pending-changes/" + changeId)).andExpect(jsonPath("$.canDecide").value(false));
        as(admin2, get("/api/v1/admin/pending-changes/" + changeId)).andExpect(jsonPath("$.canDecide").value(true));

        approve(admin1, changeId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANGE_OWN_PROPOSAL"));
        approve(admin2, changeId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(admin2.subject()));

        as(admin1, get("/api/v1/admin/cabinets/" + cabinetId))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.code").value("CUSTOMERS"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(auditActions()).contains("CHANGE_PROPOSED", "CHANGE_APPROVED", "CABINET_CREATED");
        AuditEvent created = audit("CABINET_CREATED");
        assertThat(created.getActorId()).isEqualTo(admin2.subject());
        assertThat(created.getDetails()).contains(admin1.subject()).contains(changeId);
    }

    @Test
    void cabinetUpdateAndDeactivationNeedIfMatchAndApproval() throws Exception {
        String cabinetId = createApproved("/api/v1/admin/cabinets", cabinet("LOANS", "Loans", 100, 1000));

        as(admin1, put("/api/v1/admin/cabinets/" + cabinetId).contentType(MediaType.APPLICATION_JSON)
                .content(cabinet("LOANS", "Loans and credit", 200, 5000, "ACTIVE")))
                .andExpect(status().isPreconditionRequired());
        String update = id(as(admin1, put("/api/v1/admin/cabinets/" + cabinetId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(cabinet("LOANS", "Loans and credit", 200, 5000,
                        "ACTIVE"))).andExpect(status().isAccepted()).andReturn());
        approve(admin2, update).andExpect(status().isOk());
        as(admin1, get("/api/v1/admin/cabinets/" + cabinetId))
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.name").value("Loans and credit"))
                .andExpect(jsonPath("$.maxFileSizeMb").value(200));

        String deactivate = id(as(admin2, delete("/api/v1/admin/cabinets/" + cabinetId).header("If-Match", "\"1\""))
                .andExpect(status().isAccepted()).andReturn());
        approve(admin1, deactivate).andExpect(status().isOk());
        as(admin1, get("/api/v1/admin/cabinets/" + cabinetId)).andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    void onePendingChangePerSubjectAndProposalsNeedTheCurrentVersion() throws Exception {
        String cabinetId = createApproved("/api/v1/admin/cabinets", cabinet("TRADE", "Trade", 100, 1000));
        String first = id(as(admin1, put("/api/v1/admin/cabinets/" + cabinetId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(cabinet("TRADE", "Trade A", 100, 1000, "ACTIVE")))
                .andExpect(status().isAccepted()).andReturn());

        as(admin2, put("/api/v1/admin/cabinets/" + cabinetId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(cabinet("TRADE", "Trade B", 100, 1000, "ACTIVE")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANGE_PENDING"));

        as(admin1, post("/api/v1/admin/pending-changes/" + first + "/cancel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        String rename = id(as(admin2, put("/api/v1/admin/cabinets/" + cabinetId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(cabinet("TRADE", "Trade B", 100, 1000, "ACTIVE")))
                .andExpect(status().isAccepted()).andReturn());
        approve(admin1, rename).andExpect(status().isOk());

        as(admin1, put("/api/v1/admin/cabinets/" + cabinetId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(cabinet("TRADE", "Trade C", 100, 1000, "ACTIVE")))
                .andExpect(status().isPreconditionFailed());
        as(admin1, put("/api/v1/admin/cabinets/" + cabinetId).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content(cabinet("TRADE", "Trade C", 100, 1000, "ACTIVE")))
                .andExpect(status().isAccepted());
    }

    @Test
    void approvalOfAChangeWhoseSubjectChangedMeanwhileGoesStale() throws Exception {
        String lovId = createApproved("/api/v1/admin/lovs", lov("COLOUR", "RED", "BLUE"));
        // field list change and LOV change are different subjects: both can be pending at once
        String typeId = createApproved("/api/v1/admin/document-types", documentType(cabinetId(), "CAR_PHOTO",
                "[" + field("colour", "LOV", null, "COLOUR", false) + "]"));
        String fieldsChange = id(as(admin1, put("/api/v1/admin/document-types/" + typeId + "/fields")
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("[" + field("colour", "LOV", null, "COLOUR", false) + "," + field("plate", "TEXT", 20, null,
                        false) + "]")).andExpect(status().isAccepted()).andReturn());
        String typeChange = id(as(admin1, put("/api/v1/admin/document-types/" + typeId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(documentTypeUpdate("Car photo (renamed)")))
                .andExpect(status().isAccepted()).andReturn());

        approve(admin2, typeChange).andExpect(status().isOk());
        approve(admin2, fieldsChange)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANGE_STALE"))
                .andExpect(jsonPath("$.change.status").value("STALE"));

        as(admin2, get("/api/v1/admin/pending-changes/" + fieldsChange)).andExpect(jsonPath("$.status").value("STALE"));
        as(admin1, get("/api/v1/admin/document-types/" + typeId + "/fields")).andExpect(jsonPath("$.length()").value(1));
        assertThat(lovId).isNotBlank();
    }

    @Test
    void rejectNeedsACommentAndOnlyOthersCanRejectProposersCancel() throws Exception {
        String change = id(as(admin1, post("/api/v1/admin/cabinets").contentType(MediaType.APPLICATION_JSON)
                .content(cabinet("HR", "HR", 10, 10))).andReturn());

        as(admin2, post("/api/v1/admin/pending-changes/" + change + "/reject")).andExpect(status().isUnprocessableContent());
        as(admin1, post("/api/v1/admin/pending-changes/" + change + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"no\"}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANGE_OWN_PROPOSAL"));
        as(admin2, post("/api/v1/admin/pending-changes/" + change + "/cancel")).andExpect(status().isForbidden());
        as(admin2, post("/api/v1/admin/pending-changes/" + change + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Not needed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionComment").value("Not needed"));
        approve(admin2, change).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANGE_NOT_PENDING"));
        as(admin1, get("/api/v1/admin/cabinets")).andExpect(jsonPath("$.items[*].code").value(not(hasItem("HR"))));
    }

    @Test
    void concurrentApprovalsApplyOnce() throws Exception {
        String change = id(as(admin1, post("/api/v1/admin/cabinets").contentType(MediaType.APPLICATION_JSON)
                .content(cabinet("RACE", "Race", 10, 10))).andReturn());
        TestJwts.Builder admin3 = TestJwts.user(tenant.code()).username("tadmin3").roles("TENANT_ADMIN");
        as(admin3, get("/api/v1/me")).andExpect(status().isOk());

        List<Integer> statuses = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = List.of(admin2, admin3).stream().map(approver -> Thread.ofVirtual().start(() -> {
            try {
                statuses.add(approve(approver, change).andReturn().getResponse().getStatus());
            } catch (Exception e) {
                statuses.add(-1);
            }
        })).toList();
        for (Thread thread : threads) {
            thread.join();
        }

        // decisions on one change are serialised: the second approver finds it already approved
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        as(admin1, get("/api/v1/admin/cabinets")).andExpect(jsonPath("$.items[*].code").value(contains("RACE")));
        assertThat(auditActions().stream().filter("CABINET_CREATED"::equals)).hasSize(1);
    }

    // ---------------------------------------------------------------- authorization and validation

    @Test
    void onlyTenantAdminsAdministerMetadata() throws Exception {
        String body = cabinet("NOPE", "Nope", 10, 10);
        as(maker, post("/api/v1/admin/cabinets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        as(securityAdmin, post("/api/v1/admin/cabinets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        as(maker, get("/api/v1/admin/document-types")).andExpect(status().isForbidden());
        as(maker, get("/api/v1/admin/lovs")).andExpect(status().isForbidden());
        // the inbox is open to everyone but shows only changes the caller may decide or proposed
        as(maker, get("/api/v1/admin/pending-changes")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/admin/cabinets")).andExpect(status().isUnauthorized());

        String change = id(as(admin1, post("/api/v1/admin/cabinets").contentType(MediaType.APPLICATION_JSON)
                .content(cabinet("VISIBLE", "Visible", 10, 10))).andReturn());
        // security admins approve access changes, not metadata: the change does not exist for them
        as(securityAdmin, get("/api/v1/admin/pending-changes")).andExpect(jsonPath("$.total").value(0));
        approve(securityAdmin, change).andExpect(status().isNotFound());
        // nor for admins of another tenant
        as(otherTenantAdmin, get("/api/v1/admin/pending-changes/" + change)).andExpect(status().isNotFound());
        approve(otherTenantAdmin, change).andExpect(status().isNotFound());
        as(otherTenantAdmin, get("/api/v1/admin/cabinets")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void invalidAndDuplicateProposalsAreRejectedUpFront() throws Exception {
        as(admin1, post("/api/v1/admin/cabinets").contentType(MediaType.APPLICATION_JSON)
                .content(cabinet("bad code", "", 0, 0)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "code", "name", "maxFileSizeMb")));

        createApproved("/api/v1/admin/cabinets", cabinet("DUP", "Dup", 10, 10));
        as(admin1, post("/api/v1/admin/cabinets").contentType(MediaType.APPLICATION_JSON).content(cabinet("DUP", "x",
                10, 10))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODE_IN_USE"));

        as(admin1, post("/api/v1/admin/cabinets").contentType(MediaType.APPLICATION_JSON).content(cabinet("TWICE",
                "x", 10, 10))).andExpect(status().isAccepted());
        as(admin2, post("/api/v1/admin/cabinets").contentType(MediaType.APPLICATION_JSON).content(cabinet("TWICE",
                "y", 10, 10))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANGE_PENDING"));
    }

    // ---------------------------------------------------------------- document types, fields, LOVs

    @Test
    void documentTypeIsCreatedWithItsFieldsAndFieldsCanBeReplaced() throws Exception {
        createApproved("/api/v1/admin/lovs", lov("CURRENCY", "OMR", "USD"));
        String cabinetId = cabinetId();
        String typeId = createApproved("/api/v1/admin/document-types", documentType(cabinetId, "LOAN_AGREEMENT",
                "[" + field("amount", "NUMBER", null, null, true) + "," + field("currency", "LOV", null, "CURRENCY",
                        true) + "]"));

        as(admin1, get("/api/v1/admin/document-types/" + typeId))
                .andExpect(jsonPath("$.code").value("LOAN_AGREEMENT"))
                .andExpect(jsonPath("$.channels").value(contains("INTEGRATION", "UI")))
                .andExpect(jsonPath("$.requiredStandardFields").value(contains("cif", "contract_ref")))
                .andExpect(jsonPath("$.fields[*].key").value(contains("amount", "currency")));

        String replace = id(as(admin1, put("/api/v1/admin/document-types/" + typeId + "/fields")
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("[" + field("currency", "LOV", null, "CURRENCY", true) + "," + field("note", "TEXT", null,
                        null, false) + "]"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("added note; removed amount")))
                .andReturn());
        approve(admin2, replace).andExpect(status().isOk());

        as(admin1, get("/api/v1/admin/document-types/" + typeId + "/fields"))
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$[*].key").value(contains("currency", "note")))
                .andExpect(jsonPath("$[1].maxLength").value(255));
    }

    @Test
    void invalidFieldDefinitionsAreRejected() throws Exception {
        as(admin1, post("/api/v1/admin/document-types").contentType(MediaType.APPLICATION_JSON)
                .content(documentType(cabinetId(), "BAD_TYPE", "[" + field("cif", "TEXT", 20, null, false) + ","
                        + field("colour", "LOV", null, "NO_SUCH_LOV", false) + "]")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "FIELD_KEY_RESERVED", "FIELD_LOV_UNKNOWN")));
    }

    @Test
    void listsOfValuesInUseCannotBeDeletedAndRemovedValuesAreDeactivated() throws Exception {
        String lovId = createApproved("/api/v1/admin/lovs", lov("TITLE", "MR", "MS", "DR"));
        createApproved("/api/v1/admin/document-types", documentType(cabinetId(), "LETTER",
                "[" + field("salutation", "LOV", null, "TITLE", false) + "]"));

        as(admin1, delete("/api/v1/admin/lovs/" + lovId).header("If-Match", "\"0\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOV_IN_USE"));

        String update = id(as(admin1, put("/api/v1/admin/lovs/" + lovId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(lov("TITLE", "MR", "MS"))).andReturn());
        approve(admin2, update).andExpect(status().isOk());
        as(admin1, get("/api/v1/admin/lovs/" + lovId))
                .andExpect(jsonPath("$.values[?(@.code == 'DR')].active").value(contains(false)))
                .andExpect(jsonPath("$.values[?(@.code == 'MR')].active").value(contains(true)));
    }

    // ---------------------------------------------------------------- folders

    @Test
    void unusedListsAndDocumentTypesAreRetiredThroughApproval() throws Exception {
        String lovId = createApproved("/api/v1/admin/lovs", lov("SPARE", "A", "B"));
        String deleteLov = id(as(admin1, delete("/api/v1/admin/lovs/" + lovId).header("If-Match", "\"0\""))
                .andExpect(status().isAccepted()).andReturn());
        approve(admin2, deleteLov).andExpect(status().isOk());

        String typeId = createApproved("/api/v1/admin/document-types", documentType(cabinetId(), "MEMO", "[]"));
        String deactivate = id(as(admin1, delete("/api/v1/admin/document-types/" + typeId)
                .header("If-Match", "\"0\"")).andExpect(status().isAccepted()).andReturn());
        approve(admin2, deactivate).andExpect(status().isOk());
        as(admin1, get("/api/v1/admin/document-types/" + typeId)).andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    void folderTreeCanBeBuiltMovedAndPruned() throws Exception {
        String cabinetId = cabinetId();
        String branchFiles = createApproved("/api/v1/admin/cabinets/" + cabinetId + "/folders",
                "{\"name\":\"Branch files\"}");
        String year = createApproved("/api/v1/admin/cabinets/" + cabinetId + "/folders",
                "{\"name\":\"2026\",\"parentId\":\"" + branchFiles + "\"}");
        as(admin1, get("/api/v1/admin/folders/" + year)).andExpect(jsonPath("$.path").value("/Branch files/2026"));

        // siblings cannot share a name, ignoring case
        as(admin1, post("/api/v1/admin/cabinets/" + cabinetId + "/folders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"BRANCH FILES\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FOLDER_NAME_IN_USE"));
        // no cycles
        as(admin1, put("/api/v1/admin/folders/" + branchFiles).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Branch files\",\"parentId\":\"" + year
                        + "\"}")).andExpect(status().isUnprocessableContent());
        // a folder with sub-folders cannot be deleted
        as(admin1, delete("/api/v1/admin/folders/" + branchFiles).header("If-Match", "\"0\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FOLDER_NOT_EMPTY"));

        String move = id(as(admin1, put("/api/v1/admin/folders/" + year).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Archive 2026\"}")).andReturn());
        approve(admin2, move).andExpect(status().isOk());
        as(admin1, get("/api/v1/admin/folders/" + year)).andExpect(jsonPath("$.path").value("/Archive 2026"))
                .andExpect(jsonPath("$.parentId").doesNotExist());
        as(admin1, get("/api/v1/admin/cabinets/" + cabinetId + "/folders"))
                .andExpect(jsonPath("$.items[*].name").value(contains("Archive 2026", "Branch files")));

        String remove = id(as(admin1, delete("/api/v1/admin/folders/" + branchFiles).header("If-Match", "\"0\""))
                .andExpect(status().isAccepted()).andReturn());
        approve(admin2, remove).andExpect(status().isOk());
        as(admin1, get("/api/v1/admin/folders/" + branchFiles)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- languages (ADR-0023)

    @Test
    void arabicNamesAreOptional() throws Exception {
        String lovBody = "{\"code\":\"GRADE\",\"name\":\"Grades\",\"values\":[{\"code\":\"A\",\"labelEn\":\"Grade A\","
                + "\"displayOrder\":0,\"active\":true},{\"code\":\"B\",\"labelEn\":\"Grade B\",\"labelAr\":\"  \","
                + "\"displayOrder\":1,\"active\":true}]}";
        String lovId = createApproved("/api/v1/admin/lovs", lovBody);
        String englishOnlyField = field("grade", "LOV", null, "GRADE", true).replace(",\"labelAr\":\"حقل\"", "");
        String typeId = createApproved("/api/v1/admin/document-types", documentType(cabinetId(), "ENGLISH_ONLY",
                "[" + englishOnlyField + "," + field("note", "TEXT", null, null, false).replace("حقل", " ") + "]")
                .replace(",\"nameAr\":\"نوع\"", ""));

        as(admin1, get("/api/v1/admin/document-types/" + typeId))
                .andExpect(jsonPath("$.nameEn").value("ENGLISH_ONLY"))
                .andExpect(jsonPath("$.nameAr").doesNotExist())
                .andExpect(jsonPath("$.fields[0].labelAr").doesNotExist())
                .andExpect(jsonPath("$.fields[1].labelAr").doesNotExist());
        as(admin1, get("/api/v1/admin/lovs/" + lovId))
                .andExpect(jsonPath("$.values[*].labelEn").value(contains("Grade A", "Grade B")))
                .andExpect(jsonPath("$.values[0].labelAr").doesNotExist())
                .andExpect(jsonPath("$.values[1].labelAr").doesNotExist());

        // an English name is still required, and an Arabic one keeps its length limit
        as(admin1, post("/api/v1/admin/document-types").contentType(MediaType.APPLICATION_JSON)
                .content(documentType(cabinetId(), "NO_NAMES", "[]")
                        .replace("\"nameEn\":\"NO_NAMES\"", "\"nameEn\":\"\"")
                        .replace("\"nameAr\":\"نوع\"", "\"nameAr\":\"" + "ع".repeat(201) + "\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("nameEn",
                        "nameAr")));
    }

    @Test
    void arabicIsSwitchedOnOnlyAfterASecondAdminApproves() throws Exception {
        as(admin1, get("/api/v1/admin/languages"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.languages").value(contains("en")))
                .andExpect(jsonPath("$.supported").value(contains("en", "ar")))
                .andExpect(jsonPath("$.defaultLanguage").value("en"));
        as(maker, get("/api/v1/me")).andExpect(jsonPath("$.languages").value(contains("en")));

        as(admin1, put("/api/v1/admin/languages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"languages\":[\"en\",\"ar\"]}")).andExpect(status().isPreconditionRequired());
        String change = id(as(admin1, put("/api/v1/admin/languages").header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"languages\":[\"ar\",\"en\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.subjectType").value("TENANT_LANGUAGES"))
                .andExpect(jsonPath("$.summary").value("Offer Arabic in the screens"))
                .andReturn());
        as(maker, get("/api/v1/me")).andExpect(jsonPath("$.languages").value(contains("en")));

        approve(admin1, change).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANGE_OWN_PROPOSAL"));
        approve(admin2, change).andExpect(status().isOk());

        as(maker, get("/api/v1/me")).andExpect(jsonPath("$.languages").value(contains("en", "ar")));
        as(admin1, get("/api/v1/admin/languages")).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.languages").value(contains("en", "ar")));
        // the other tenant is not affected
        as(otherTenantAdmin, get("/api/v1/me")).andExpect(jsonPath("$.languages").value(contains("en")));
        AuditEvent changed = audit("TENANT_LANGUAGES_CHANGED");
        assertThat(changed.getActorId()).isEqualTo(admin2.subject());
        assertThat(changed.getDetails()).contains("en,ar").contains(admin1.subject());

        // a proposal based on the old version is refused
        as(admin1, put("/api/v1/admin/languages").header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"languages\":[\"en\"]}")).andExpect(status().isPreconditionFailed());
    }

    @Test
    void englishStaysOnAndOnlySupportedLanguagesAreAccepted() throws Exception {
        as(admin1, put("/api/v1/admin/languages").header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"languages\":[\"ar\",\"fr\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "LANGUAGE_UNKNOWN", "LANGUAGE_ENGLISH_REQUIRED")));
    }

    @Test
    void onlyTenantAdminsSeeAndChangeTheLanguages() throws Exception {
        as(maker, get("/api/v1/admin/languages")).andExpect(status().isForbidden());
        as(securityAdmin, get("/api/v1/admin/languages")).andExpect(status().isForbidden());
        as(maker, put("/api/v1/admin/languages").header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"languages\":[\"en\",\"ar\"]}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/v1/admin/languages")).andExpect(status().isUnauthorized());

        String change = id(as(admin1, put("/api/v1/admin/languages").header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"languages\":[\"en\",\"ar\"]}")).andReturn());
        approve(securityAdmin, change).andExpect(status().isNotFound());
        approve(otherTenantAdmin, change).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private String cabinetId() throws Exception {
        return createApproved("/api/v1/admin/cabinets", cabinet("C" + System.nanoTime() % 1_000_000_000, "Cabinet",
                100, 1000));
    }

    /** Proposes as admin1, approves as admin2, returns the new subject's ID. */
    private String createApproved(String path, String body) throws Exception {
        MvcResult proposed = as(admin1, post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn();
        approve(admin2, id(proposed)).andExpect(status().isOk());
        return json(proposed).get("subjectId").asString();
    }

    private ResultActions approve(TestJwts.Builder user, String changeId) throws Exception {
        return as(user, post("/api/v1/admin/pending-changes/" + changeId + "/approve"));
    }

    private ResultActions as(TestJwts.Builder user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", user.bearer()));
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String id(MvcResult result) throws Exception {
        return json(result).get("id").asString();
    }

    private List<String> auditActions() {
        return inTenant(() -> auditEvents.findAll().stream().map(AuditEvent::getAction).toList());
    }

    private AuditEvent audit(String action) {
        return inTenant(() -> auditEvents.findByActionOrderByOccurredAt(action).getFirst());
    }

    private <T> T inTenant(Supplier<T> work) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> work.get()));
    }

    private static String cabinet(String code, String name, int maxFileSizeMb, long quotaMb) {
        return cabinet(code, name, maxFileSizeMb, quotaMb, null);
    }

    private static String cabinet(String code, String name, int maxFileSizeMb, long quotaMb, String status) {
        return JSON.writeValueAsString(new java.util.HashMap<>(Map.of("code", code, "name", name, "maxFileSizeMb",
                maxFileSizeMb, "quotaMb", quotaMb, "defaultProcessKey", "single-approval",
                "status", status == null ? "ACTIVE" : status)));
    }

    private static String lov(String code, String... values) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            items.append(i == 0 ? "" : ",").append("{\"code\":\"").append(values[i]).append("\",\"labelEn\":\"")
                    .append(values[i]).append("\",\"labelAr\":\"").append("قيمة ").append(values[i])
                    .append("\",\"displayOrder\":").append(i).append(",\"active\":true}");
        }
        return "{\"code\":\"" + code + "\",\"name\":\"" + code + " list\",\"values\":[" + items + "]}";
    }

    private static String documentType(String cabinetId, String code, String fieldsJson) {
        return "{\"cabinetId\":\"" + cabinetId + "\",\"code\":\"" + code + "\",\"nameEn\":\"" + code
                + "\",\"nameAr\":\"نوع\",\"category\":\"TEST\",\"channels\":[\"UI\",\"INTEGRATION\"],"
                + "\"defaultClassification\":\"CONFIDENTIAL\",\"processKey\":\"single-approval\","
                + "\"metaChangeNeedsApproval\":true,\"deleteRequiresApproval\":true,\"declareRecordOnApproval\":false,"
                + "\"requiredStandardFields\":[\"contract_ref\",\"cif\"],\"fields\":" + fieldsJson + "}";
    }

    private static String documentTypeUpdate(String nameEn) {
        return "{\"nameEn\":\"" + nameEn + "\",\"nameAr\":\"نوع\",\"category\":\"TEST\",\"channels\":[\"UI\"],"
                + "\"defaultClassification\":\"INTERNAL\",\"metaChangeNeedsApproval\":false,"
                + "\"deleteRequiresApproval\":false,\"declareRecordOnApproval\":false,\"requiredStandardFields\":[],"
                + "\"status\":\"ACTIVE\"}";
    }

    private static String field(String key, String type, Integer maxLength, String lovCode, boolean mandatory) {
        return "{\"key\":\"" + key + "\",\"labelEn\":\"" + key + "\",\"labelAr\":\"حقل\",\"dataType\":\"" + type
                + "\",\"mandatory\":" + mandatory + ",\"maxLength\":" + maxLength + ",\"lovCode\":"
                + (lovCode == null ? "null" : "\"" + lovCode + "\"") + ",\"searchable\":true,\"facet\":false,"
                + "\"sensitive\":false,\"displayOrder\":1,\"displayRow\":1}";
    }
}
