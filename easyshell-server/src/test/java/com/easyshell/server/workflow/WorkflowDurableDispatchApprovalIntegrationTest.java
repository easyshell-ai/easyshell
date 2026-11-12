package com.easyshell.server.workflow;

import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.dto.TaskTargetSnapshot;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowDispatchApprovalBindingRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunProvenanceRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.security.JwtUtils;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostIdentityService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.node.ScriptExecNodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real durable resume and dispatch evidence; executor spies inject scoped recovery faults only.
 * Skipped unless the runner supplies an isolated synthetic EASYSHELL_DISPATCH_HMAC_KEY.
 * Injected executor errors cover conservative uncertainty, not a real host-side timeout or pool rejection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "EASYSHELL_DISPATCH_HMAC_KEY", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_durable_dispatch_approval_integration;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=warn",
        "easyshell.workflow.approval.dispatch-enabled=true",
        "easyshell.workflow.engine.durable-resume-enabled=true",
        "easyshell.workflow.engine.min-trigger-interval-sec=0",
        "easyshell.workflow.engine.wakeup-interval-sec=1",
        "easyshell.workflow.engine.poll-interval-ms=50",
        "easyshell.workflow.engine.max-concurrent-runs=2"
})
class WorkflowDurableDispatchApprovalIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(40);

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
    @Autowired WorkflowApprovalRepository approvals;
    @Autowired WorkflowDispatchApprovalBindingRepository bindings;
    @Autowired TaskTargetSnapshotRepository snapshots;
    @Autowired WorkflowRunProvenanceRepository provenance;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowEngine engine;
    @MockitoSpyBean ScriptExecNodeExecutor scriptExecutor;

    @Test
    void highRiskApprovalResumesSameAttemptAndClosesDurableBatchAfterOneTask() throws Exception {
        Fixture fixture = start(true);
        try {
            long approvalId = waitingApproval(fixture, "script");
            var waiting = nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), "script", 1).orElseThrow();
            var original = binding(approvalId);
            assertThat(original.nodeRunId()).isEqualTo(waiting.getId());
            assertThat(original.attempt()).isEqualTo(1);
            assertThat(original.consumedAt()).isNull();
            assertThat(original.taskId()).isNull();
            assertThat(count("SELECT COUNT(*) FROM task WHERE created_by = ?", fixture.ownerId())).isZero();
            assertThat(count("SELECT COUNT(*) FROM job WHERE agent_id = ?", fixture.agentId())).isZero();
            verify(agentTransport, never()).dispatchJob(eq(fixture.agentId()), any(Job.class), anyString(), anyInt());
            assertReadableApproval(fixture, approvalId);

            decide(fixture, approvalId, "approved");
            Job job = completeJob(fixture);
            await().atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo("succeeded");
                assertClosedRecovery(fixture.runId(), waiting.getId(), "SUCCEEDED");
            });
            var finished = nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), "script", 1).orElseThrow();
            assertThat(nodeRuns.findByRunId(fixture.runId())).hasSize(1);
            assertThat(finished.getId()).isEqualTo(waiting.getId());
            assertThat(finished.getStatus()).isEqualTo("succeeded");
            assertThat(finished.getTaskId()).isEqualTo(job.getTaskId());
            assertThat(finished.getApprovalId()).isEqualTo(approvalId);
            var consumed = binding(approvalId);
            assertThat(consumed.consumedAt()).isNotNull();
            assertThat(consumed.taskId()).isEqualTo(job.getTaskId());
            assertThat(consumed.nodeRunId()).isEqualTo(original.nodeRunId());
            assertThat(consumed.attempt()).isEqualTo(original.attempt());
            assertThat(consumed.targets()).isEqualTo(original.targets());
            assertThat(consumed.scriptHmac()).isEqualTo(original.scriptHmac());
            assertThat(consumed.intentHmac()).isEqualTo(original.intentHmac());
            assertThat(count("SELECT COUNT(*) FROM workflow_dispatch_approval_binding b JOIN workflow_approval a "
                    + "ON a.id = b.approval_id WHERE a.run_id = ?", fixture.runId())).isEqualTo(1L);
            assertOneTask(fixture, job);
        } finally {
            cancel(fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"approved", "rejected"})
    void ordinaryApprovalDecisionClosesDurableMemberWithoutUncertainty(String decision) throws Exception {
        Fixture fixture = start(false);
        try {
            Job job = completeJob(fixture);
            long approvalId = waitingApproval(fixture, "review");
            var waiting = nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), "review", 1).orElseThrow();
            assertThat(provenance.findComplete(fixture.runId()).orElseThrow().taskIds()).containsExactly(job.getTaskId());
            assertReadableApproval(fixture, approvalId);
            decide(fixture, approvalId, decision);

            boolean approved = "approved".equals(decision);
            await().atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus())
                        .isEqualTo(approved ? "succeeded" : "failed");
                assertClosedRecovery(fixture.runId(), waiting.getId(), approved ? "SUCCEEDED" : "FAILED");
            });
            assertThat(approvals.findById(approvalId).orElseThrow().getStatus()).isEqualTo(decision);
            var finished = nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), "review", 1).orElseThrow();
            assertThat(finished.getId()).isEqualTo(waiting.getId());
            assertThat(finished.getStatus()).isEqualTo(approved ? "succeeded" : "failed");
            assertThat(finished.getTaskId()).isNull();
            assertOneTask(fixture, job);
        } finally {
            cancel(fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NODE_TIMEOUT", "NODE_INTERNAL"})
    void acceptedRecoveryExecutorErrorKeepsUncertainSlotAndCannotBeReplayed(String errorCode) throws Exception {
        Fixture fixture = start(true);
        ScriptExecNodeExecutor target = AopTestUtils.getUltimateTargetObject(scriptExecutor);
        AtomicInteger acceptedExecutions = new AtomicInteger();
        try {
            long approvalId = waitingApproval(fixture, "script");
            var waiting = nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), "script", 1).orElseThrow();
            var originalBinding = binding(approvalId);
            doAnswer(call -> {
                NodeContext ctx = call.getArgument(0);
                if (fixture.runId().equals(ctx.run().getId())
                        && Long.valueOf(approvalId).equals(ctx.nodeRun().getApprovalId())) {
                    // This callback is reached by the real executor pool after its durable delivery barrier.
                    // It does not claim that a remote agent accepted work: no TaskService call is made.
                    assertThat(jdbc.queryForList("SELECT claim_state FROM workflow_recovery_member "
                                    + "WHERE run_id = ? AND node_run_id = ? AND attempt = 1",
                            String.class, fixture.runId(), waiting.getId())).containsExactly("ENTERED");
                    assertThat(approvals.findById(approvalId).orElseThrow().getStatus()).isEqualTo("approved");
                    acceptedExecutions.incrementAndGet();
                    throw new NodeFailedException(errorCode, "Injected error after recovery executor entry");
                }
                return call.callRealMethod();
            }).when(target).execute(any(NodeContext.class));

            decide(fixture, approvalId, "approved");
            await().atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(acceptedExecutions.get()).isEqualTo(1);
                assertUncertainRecovery(fixture, waiting.getId(), originalBinding);
            });
            var slot = jdbc.queryForMap("SELECT last_round, active_round FROM workflow_recovery_run WHERE run_id = ?",
                    fixture.runId());
            long batches = count("SELECT COUNT(*) FROM workflow_recovery_batch WHERE run_id = ?", fixture.runId());
            long members = count("SELECT COUNT(*) FROM workflow_recovery_member WHERE run_id = ?", fixture.runId());

            mvc.perform(post("/api/v1/workflow/approvals/{id}/decision", approvalId)
                            .header("Authorization", fixture.token()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"approved\",\"comment\":\"duplicate after uncertainty\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("APPROVAL_DECIDED"));
            engine.submitResume(fixture.runId());
            engine.submitResume(fixture.runId());
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertUncertainRecovery(fixture, waiting.getId(), originalBinding);
                assertThat(acceptedExecutions.get()).isEqualTo(1);
                assertThat(jdbc.queryForMap("SELECT last_round, active_round FROM workflow_recovery_run WHERE run_id = ?",
                        fixture.runId())).isEqualTo(slot);
                assertThat(count("SELECT COUNT(*) FROM workflow_recovery_batch WHERE run_id = ?", fixture.runId()))
                        .isEqualTo(batches);
                assertThat(count("SELECT COUNT(*) FROM workflow_recovery_member WHERE run_id = ?", fixture.runId()))
                        .isEqualTo(members);
            });

            cancel(fixture);
            await().atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo("cancelled");
                assertUncertainSlot(fixture.runId(), waiting.getId());
                assertThat(jdbc.queryForMap("SELECT last_round, active_round FROM workflow_recovery_run WHERE run_id = ?",
                        fixture.runId())).isEqualTo(slot);
                assertNoTaskOrDispatch(fixture);
                assertThat(binding(approvalId)).isEqualTo(originalBinding);
            });
        } finally {
            try {
                cancel(fixture);
            } finally {
                reset(target);
            }
        }
    }

    private void assertUncertainRecovery(Fixture fixture, String nodeRunId,
                                         WorkflowDispatchApprovalBindingRepository.Evidence originalBinding) {
        assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo("running");
        var node = nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), "script", 1).orElseThrow();
        assertThat(node.getId()).isEqualTo(nodeRunId);
        assertThat(node.getStatus()).isEqualTo("running");
        assertThat(node.getTaskId()).isNull();
        assertThat(nodeRuns.findByRunId(fixture.runId())).hasSize(1);
        assertThat(binding(originalBinding.approvalId())).isEqualTo(originalBinding);
        assertUncertainSlot(fixture.runId(), nodeRunId);
        assertNoTaskOrDispatch(fixture);
    }

    private void assertUncertainSlot(String runId, String nodeRunId) {
        assertThat(jdbc.queryForList("SELECT claim_state FROM workflow_recovery_member "
                        + "WHERE run_id = ? AND node_run_id = ? AND attempt = 1", String.class, runId, nodeRunId))
                .containsExactly("UNCERTAIN");
        assertThat(count("SELECT COUNT(*) FROM workflow_recovery_run WHERE run_id = ? AND active_round IS NOT NULL", runId))
                .isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM workflow_recovery_batch b JOIN workflow_recovery_run r "
                + "ON r.run_id = b.run_id AND r.active_round = b.claim_round "
                + "WHERE b.run_id = ? AND b.closed_at IS NULL", runId)).isEqualTo(1L);
    }

    private void assertNoTaskOrDispatch(Fixture fixture) {
        assertThat(count("SELECT COUNT(*) FROM task WHERE created_by = ?", fixture.ownerId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM job WHERE agent_id = ?", fixture.agentId())).isZero();
        assertThat(provenance.findComplete(fixture.runId()).orElseThrow().taskIds()).isEmpty();
        assertThat(fixture.dispatched()).isEmpty();
        verify(agentTransport, never()).dispatchJob(eq(fixture.agentId()), any(Job.class), anyString(), anyInt());
    }

    private void assertClosedRecovery(String runId, String nodeRunId, String state) {
        assertThat(jdbc.queryForList("SELECT claim_state FROM workflow_recovery_member "
                + "WHERE run_id = ? AND node_run_id = ? AND attempt = 1", String.class, runId, nodeRunId))
                .containsExactly(state);
        assertThat(count("SELECT COUNT(*) FROM workflow_recovery_member WHERE run_id = ? "
                + "AND claim_state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')", runId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM workflow_recovery_batch WHERE run_id = ?", runId)).isPositive();
        assertThat(count("SELECT COUNT(*) FROM workflow_recovery_batch WHERE run_id = ? AND closed_at IS NULL", runId))
                .isZero();
        assertThat(jdbc.queryForList("SELECT active_round FROM workflow_recovery_run WHERE run_id = ?",
                Long.class, runId)).containsExactly((Long) null);
    }

    private void assertOneTask(Fixture fixture, Job job) {
        assertThat(count("SELECT COUNT(*) FROM task WHERE created_by = ?", fixture.ownerId())).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM job WHERE agent_id = ?", fixture.agentId())).isEqualTo(1L);
        assertThat(tasks.findById(job.getTaskId()).orElseThrow().getStatus()).isEqualTo(2);
        assertThat(jobs.findByTaskId(job.getTaskId())).hasSize(1);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(2);
        assertThat(snapshots.findComplete(job.getTaskId()).orElseThrow().targets())
                .containsExactly(new TaskTargetSnapshot.Target(fixture.agentId(), 1L));
        assertThat(provenance.findComplete(fixture.runId()).orElseThrow().taskIds()).containsExactly(job.getTaskId());
        assertThat(fixture.dispatched()).isEmpty();
        verify(agentTransport, times(1)).dispatchJob(eq(fixture.agentId()), any(Job.class), eq(fixture.script()), eq(60));
    }

    private record Fixture(String runId, Long ownerId, String token, String agentId, String script,
                           LinkedBlockingQueue<Job> dispatched) { }

    private Fixture start(boolean highRisk) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User owner = new User();
        owner.setUsername("durable-" + suffix);
        owner.setEmail(owner.getUsername() + "@easyshell.test");
        owner.setPassword(passwords.encode("Integration#2026"));
        owner.setRole("super_admin");
        owner.setStatus(1);
        users.saveAndFlush(owner);
        String token = "Bearer " + jwt.generateAccessToken(owner.getId(), owner.getUsername(), owner.getRole());
        String agentId = "ag-durable-" + suffix;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setHostname("durable-approval-host");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        agents.saveAndFlush(agent);
        identities.initializeExisting(List.of(agentId));
        for (var permission : List.of(HostAclService.Permission.VIEW, HostAclService.Permission.EXECUTE)) {
            acl.change(owner.getId(), agentId, "USER", owner.getId().toString(), permission, true, 1L);
            assertThat(acl.allowedForGeneration(owner.getId(), agentId, 1L, permission)).isTrue();
        }
        String script = highRisk ? "chmod --help" : "df -h";
        var dispatched = new LinkedBlockingQueue<Job>();
        when(agentTransport.dispatchJob(eq(agentId), any(Job.class), eq(script), anyInt()))
                .thenAnswer(call -> { dispatched.add(call.getArgument(1)); return true; });
        String extraNodes = highRisk ? "" : """
                ,{"key":"review","type":"approval","name":"Review","position":{"x":200,"y":0},
                  "config":{"title":"Durable review","approverRole":"ADMIN","onTimeout":"reject","timeoutSec":120}},
                 {"key":"finish","type":"wait","name":"Finish","position":{"x":400,"y":0},"config":{"durationSec":1}}
                """;
        String edges = highRisk ? "[]" : """
                [{"id":"e1","from":"script","to":"review"},{"id":"e2","from":"review","to":"finish"}]
                """;
        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip","onRejection":"abort","variables":{},
                 "nodes":[{"key":"script","type":"script-exec","name":"Script","position":{"x":0,"y":0},
                 "config":{"script":"%s","riskPolicy":"strict","timeoutSeconds":60,
                 "targets":{"mode":"explicit","agentIds":["%s"]}}}%s],"edges":%s}
                """.formatted(script, agentId, extraNodes, edges);
        long workflowId = data(mvc.perform(post("/api/v1/workflow").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"durable approval " + suffix + "\",\"visibility\":\"private\",\"category\":\"ops\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        JsonNode draft = data(mvc.perform(put("/api/v1/workflow/{id}/versions/draft", workflowId)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":" + definition + ",\"baseVersion\":0,\"expectedAbsent\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String publish = mapper.createObjectNode().put("draftId", draft.path("id").asLong())
                .put("baseVersion", draft.path("baseVersion").asInt())
                .put("definitionSha256", draft.path("definitionSha256").asText()).toString();
        mvc.perform(post("/api/v1/workflow/{id}/publish", workflowId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(publish))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.estimatedRisk").value(highRisk ? "HIGH" : "LOW"));
        String runId = data(mvc.perform(post("/api/v1/workflow/{id}/runs", workflowId)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"input\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString()).path("id").asText();
        return new Fixture(runId, owner.getId(), token, agentId, script, dispatched);
    }

    private long waitingApproval(Fixture fixture, String key) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo("waiting");
            var node = nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), key, 1).orElseThrow();
            assertThat(node.getStatus()).isEqualTo("waiting");
            assertThat(node.getApprovalId()).isNotNull();
            assertThat(approvals.findById(node.getApprovalId()).orElseThrow().getStatus()).isEqualTo("pending");
        });
        return nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), key, 1).orElseThrow().getApprovalId();
    }

    private void assertReadableApproval(Fixture fixture, long approvalId) throws Exception {
        JsonNode pending = data(mvc.perform(get("/api/v1/workflow/approvals/pending")
                        .header("Authorization", fixture.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode approval : pending) {
            if (approval.path("id").asLong() == approvalId) {
                assertThat(approval.path("runId").asText()).isEqualTo(fixture.runId());
                assertThat(approval.path("detail").isObject()).isTrue();
                return;
            }
        }
        throw new AssertionError("Owner cannot read durable approval " + approvalId);
    }

    private void decide(Fixture fixture, long approvalId, String decision) throws Exception {
        mvc.perform(post("/api/v1/workflow/approvals/{id}/decision", approvalId)
                        .header("Authorization", fixture.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode().put("decision", decision).put("comment", "durable decision").toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(decision));
    }

    private Job completeJob(Fixture fixture) throws Exception {
        Job job = fixture.dispatched().poll(40, TimeUnit.SECONDS);
        assertThat(job).as("real task reached the simulated agent").isNotNull();
        taskService.reportJobResult(new JobResultRequest(job.getId(), fixture.agentId(), 2, 0, "simulated result"),
                fixture.agentId());
        return job;
    }

    private WorkflowDispatchApprovalBindingRepository.Evidence binding(long approvalId) {
        return new TransactionTemplate(transactions).execute(tx -> bindings.find(approvalId).orElseThrow());
    }

    private long count(String sql, Object value) {
        return jdbc.queryForObject(sql, Long.class, value);
    }

    private JsonNode data(String json) throws Exception {
        return mapper.readTree(json).path("data");
    }

    private void cancel(Fixture fixture) throws Exception {
        mvc.perform(post("/api/v1/workflow/runs/{runId}/cancel", fixture.runId())
                        .header("Authorization", fixture.token()))
                .andExpect(status().isOk());
    }
}
