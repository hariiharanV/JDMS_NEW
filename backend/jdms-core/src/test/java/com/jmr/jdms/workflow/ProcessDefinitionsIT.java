package com.jmr.jdms.workflow;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * B-05 process definitions: versioned on every deployment, running instances stay on the version they started
 * with; process expressions cannot reach application beans. Deployments here are tenant-specific (a tenant's own
 * version overrides the shared default), so other tests keep using the shared definitions.
 */
@IntegrationTest
class ProcessDefinitionsIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired RepositoryService repository;
    @Autowired RuntimeService runtime;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder maker;
    TestJwts.Builder checker;

    @BeforeEach
    void setUp() {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001");
        testMetadata.create(tenant);
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        checker = TestJwts.user(tenant.code()).username("checker").roles("CHECKER").branchScope("001")
                .clearance("CONFIDENTIAL");
    }

    @Test
    void runningInstancesContinueOnTheirOriginalVersionAfterANewDeployment() throws Exception {
        String bpmn = classpath("processes/single-approval.bpmn20.xml");
        ProcessDefinition v1 = deploy(bpmn);
        String first = upload();
        ProcessInstance firstInstance = instanceOf(first);
        assertThat(firstInstance.getProcessDefinitionId()).isEqualTo(v1.getId());

        ProcessDefinition v2 = deploy(bpmn.replace("name=\"Single approval\"", "name=\"Single approval v2\""));
        assertThat(v2.getVersion()).isEqualTo(v1.getVersion() + 1);
        String second = upload();

        assertThat(instanceOf(second).getProcessDefinitionId()).isEqualTo(v2.getId());
        assertThat(instanceOf(first).getProcessDefinitionId()).isEqualTo(v1.getId());
        approveAll();
        assertThat(documentStatus(first)).isEqualTo("ACTIVE");
        assertThat(documentStatus(second)).isEqualTo("ACTIVE");
    }

    @Test
    void redeployingAnUnchangedDefinitionDoesNotCreateANewVersion() {
        String bpmn = classpath("processes/single-approval.bpmn20.xml");
        ProcessDefinition v1 = deploy(bpmn);
        assertThat(deploy(bpmn).getId()).isEqualTo(v1.getId());
    }

    @Test
    void processExpressionsCannotReachApplicationBeans() {
        String bpmn = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://jdms.test">
                  <process id="probe" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="probeBean"/>
                    <serviceTask id="probeBean" flowable:expression="${jsonMapper.toString()}"/>
                    <sequenceFlow id="f2" sourceRef="probeBean" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """;
        repository.createDeployment().addString("probe.bpmn20.xml", bpmn).tenantId(tenant.id().toString()).deploy();

        assertThatThrownBy(() -> runtime.startProcessInstanceByKeyAndTenantId("probe", tenant.id().toString()))
                .isInstanceOf(FlowableException.class).hasMessageContaining("jsonMapper");
    }

    ProcessDefinition deploy(String bpmn) {
        String deploymentId = repository.createDeployment().name("single-approval test")
                .addString("single-approval.bpmn20.xml", bpmn).tenantId(tenant.id().toString())
                .enableDuplicateFiltering().deploy().getId();
        return repository.createProcessDefinitionQuery().deploymentId(deploymentId).singleResult() != null
                ? repository.createProcessDefinitionQuery().deploymentId(deploymentId).singleResult()
                : repository.createProcessDefinitionQuery().processDefinitionKey("single-approval")
                        .processDefinitionTenantId(tenant.id().toString()).latestVersion().singleResult();
    }

    String upload() throws Exception {
        return json(api.upload(maker, "{\"documentTypeCode\":\"CONTRACT\",\"title\":\"Contract\",\"branchCode\":"
                + "\"001\"}", randomBytes(100), "c.pdf", key()).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();
    }

    ProcessInstance instanceOf(String documentId) {
        return runtime.createProcessInstanceQuery().processInstanceBusinessKey("DOCUMENT:" + documentId)
                .processInstanceTenantId(tenant.id().toString()).singleResult();
    }

    void approveAll() throws Exception {
        for (var task : json(api.as(checker, get("/api/v1/tasks")).andReturn().getResponse().getContentAsString())
                .get("items")) {
            api.as(checker, post("/api/v1/tasks/" + task.get("id").asString() + "/complete")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                    .andExpect(status().isOk());
        }
    }

    String documentStatus(String documentId) throws Exception {
        return json(api.as(maker, get("/api/v1/documents/" + documentId)).andReturn().getResponse()
                .getContentAsString()).get("status").asString();
    }

    static String classpath(String resource) {
        try (InputStream in = ProcessDefinitionsIT.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
