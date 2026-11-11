package com.easyshell.server.workflow;

import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunProvenanceRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.security.JwtUtils;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostIdentityService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.workflow.engine.NodeExecutorRegistry;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.node.ScriptExecNodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real workflow, risk gate and persistence; only the Agent transport is simulated. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_script_dispatch_integration;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=warn",
        "easyshell.workflow.engine.min-trigger-interval-sec=0",
        "easyshell.workflow.engine.poll-interval-ms=50",
        "easyshell.workflow.engine.max-concurrent-runs=2"
})
class WorkflowScriptDispatchIntegrationTest {
    private static final String AGENT = "ag-workflow-integration";
    private static final String DEFINITION = """
            {"schemaVersion":1,"defaultOnUpstreamFailure":"skip","onRejection":"abort",
             "variables":{},"nodes":[{"key":"disk","type":"script-exec","name":"disk",
             "position":{"x":0,"y":0},"config":{"script":"df -h",
             "targets":{"mode":"explicit","agentIds":["ag-workflow-integration"]},
             "timeoutSeconds":60}}],"edges":[]}
            """;

    @MockitoBean StringRedisTemplate redis;
    @MockitoBean AgentWebSocketHandler agentTransport;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtUtils jwt;
    @Autowired AgentRepository agents;
    @Autowired HostIdentityService identities;
    @Autowired HostAclService acl;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired JobRepository jobs;
    @Autowired WorkflowNodeRunRepository nodeRuns;
    @Autowired WorkflowRunRepository runs;
    @Autowired TaskTargetSnapshotRepository snapshots;
    @Autowired WorkflowRunProvenanceRepository provenance;
    @Autowired NodeExecutorRegistry registry;

    @Test
    void lowRiskWorkflowDispatchesAndRequiresRealHostPermissions() throws Exception {
        assertThat(registry.executorFor(NodeType.SCRIPT_EXEC)).isInstanceOf(ScriptExecNodeExecutor.class);
        User owner = user("workflow-owner", "super_admin");
        User stranger = user("workflow-stranger", "operator");
        String ownerToken = token(owner);
        String strangerToken = token(stranger);

        Agent agent = new Agent();
        agent.setId(AGENT);
        agent.setHostname("integration-host");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        agents.saveAndFlush(agent);
        identities.initializeExisting(List.of(AGENT));
        acl.change(owner.getId(), AGENT, "USER", owner.getId().toString(), HostAclService.Permission.EXECUTE, true, 1L);
        assertThat(acl.allowedForGeneration(owner.getId(), AGENT, 1L, HostAclService.Permission.EXECUTE)).isTrue();
        assertThat(acl.allowedForGeneration(owner.getId(), AGENT, 1L, HostAclService.Permission.VIEW)).isFalse();

        var dispatched = new LinkedBlockingQueue<Job>();
        when(agentTransport.dispatchJob(eq(AGENT), any(Job.class), eq("df -h"), anyInt()))
                .thenAnswer(call -> { dispatched.add(call.getArgument(1)); return true; });

        JsonNode created = body(mvc.perform(post("/api/v1/workflow").header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"script integration\",\"category\":\"ops\",\"visibility\":\"private\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long workflowId = created.path("data").path("id").asLong();
        JsonNode draft = body(mvc.perform(put("/api/v1/workflow/{id}/versions/draft", workflowId)
                .header("Authorization", ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"definition\":" + DEFINITION + ",\"baseVersion\":0,\"expectedAbsent\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        String publish = "{\"draftId\":" + draft.path("id").asLong() + ",\"baseVersion\":"
                + draft.path("baseVersion").asInt() + ",\"definitionSha256\":\""
                + draft.path("definitionSha256").asText() + "\"}";
        mvc.perform(post("/api/v1/workflow/{id}/publish", workflowId).header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(publish))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.estimatedRisk").value("LOW"));
        mvc.perform(get("/api/v1/workflow/{id}", workflowId).header("Authorization", strangerToken))
                .andExpect(status().isForbidden());
        String runId = body(mvc.perform(post("/api/v1/workflow/{id}/runs", workflowId)
                .header("Authorization", ownerToken).contentType(MediaType.APPLICATION_JSON).content("{\"input\":{}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
        assertThat(runId).startsWith("wfr_");

        Job dispatchedJob = dispatched.poll(30, TimeUnit.SECONDS);
        assertThat(dispatchedJob).as("after-commit dispatch reached the mock Agent").isNotNull();
        String jobId = dispatchedJob.getId();
        String taskId = dispatchedJob.getTaskId();
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(0);
        taskService.reportJobResult(new JobResultRequest(jobId, AGENT, 2, 0, "forged"), "wrong-agent");
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(0);
        taskService.reportJobResult(new JobResultRequest(jobId, AGENT, 2, 0, "simulated result"), AGENT);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo("succeeded"));

        var node = nodeRuns.findByRunIdAndNodeKeyAndAttempt(runId, "disk", 1).orElseThrow();
        assertThat(node.getStatus()).isEqualTo("succeeded");
        assertThat(node.getTaskId()).isEqualTo(taskId);
        var task = tasks.findById(taskId).orElseThrow();
        assertThat(task.getSource()).isEqualTo("workflow");
        assertThat(task.getStatus()).isEqualTo(2);
        assertThat(task.getSuccessCount()).isEqualTo(1);
        assertThat(task.getCreatedBy()).isEqualTo(owner.getId());
        assertThat(jobs.findByTaskId(taskId)).hasSize(1);
        assertThat(jobs.findById(jobId).orElseThrow().getOutput()).isEqualTo("simulated result");
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(2);
        assertThat(snapshots.findComplete(taskId).orElseThrow().targets())
                .containsExactly(new com.easyshell.server.model.dto.TaskTargetSnapshot.Target(AGENT, 1L));
        assertThat(provenance.findComplete(runId).orElseThrow().taskIds()).containsExactly(taskId);

        mvc.perform(get("/api/v1/workflow/runs/{runId}", runId).header("Authorization", ownerToken))
                .andExpect(status().isForbidden());
        acl.change(owner.getId(), AGENT, "USER", owner.getId().toString(), HostAclService.Permission.VIEW, true, 1L);
        mvc.perform(get("/api/v1/workflow/runs/{runId}", runId).header("Authorization", ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.run.status").value("succeeded"));
        mvc.perform(get("/api/v1/workflow/runs/{runId}", runId).header("Authorization", strangerToken))
                .andExpect(status().isForbidden());
    }

    private User user(String name, String role) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@easyshell.test");
        user.setPassword(passwords.encode("Integration#2026"));
        user.setRole(role);
        user.setStatus(1);
        return users.saveAndFlush(user);
    }

    private String token(User user) {
        return "Bearer " + jwt.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
    }

    private JsonNode body(String json) throws Exception {
        return mapper.readTree(json);
    }
}
