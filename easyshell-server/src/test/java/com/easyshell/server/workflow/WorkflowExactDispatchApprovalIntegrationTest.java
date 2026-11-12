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
import com.easyshell.server.workflow.engine.WorkflowWatchdog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * Real engine, task gate, ACL, approval and persistence; Redis and agent transport are mocked.
 * Skipped by default without an isolated synthetic EASYSHELL_DISPATCH_HMAC_KEY supplied by the runner.
 * Static HMAC mocks cannot provide evidence on the engine's executor threads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "EASYSHELL_DISPATCH_HMAC_KEY", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_exact_dispatch_approval_integration;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=warn",
        "easyshell.workflow.approval.dispatch-enabled=true",
        "easyshell.workflow.engine.min-trigger-interval-sec=0",
        "easyshell.workflow.engine.wakeup-interval-sec=1",
        "easyshell.workflow.engine.poll-interval-ms=50",
        "easyshell.workflow.engine.max-concurrent-runs=2"
})
class WorkflowExactDispatchApprovalIntegrationTest {
    private static final String NODE = "permissions_help";
    private static final String SCRIPT = "chmod --help";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
    @MockitoSpyBean TaskTargetSnapshotRepository snapshots;
    @Autowired WorkflowRunProvenanceRepository provenance;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowWatchdog watchdog;

    @Test
    void firstHighRiskScriptWaitsForExactApprovalAndDispatchesOnceOnTheSameAttempt() throws Exception {
        Fixture fixture = start("approved");
        String runId = fixture.runId();
        try {
            long approvalId = waitingApproval(fixture);
            var waiting = nodeRuns.findByRunIdAndNodeKeyAndAttempt(runId, NODE, 1).orElseThrow();
            var originalBinding = binding(approvalId);
            assertThat(originalBinding.nodeRunId()).isEqualTo(waiting.getId());
            assertThat(originalBinding.attempt()).isEqualTo(1);
            assertThat(originalBinding.userId()).isEqualTo(fixture.ownerId());
            assertThat(originalBinding.timeoutSec()).isEqualTo(60);
            assertThat(originalBinding.scriptHmac()).matches("[0-9a-f]{64}");
            assertThat(originalBinding.intentHmac()).matches("[0-9a-f]{64}");
            JsonNode targetEvidence = mapper.readTree(originalBinding.targets());
            assertThat(targetEvidence.size()).isEqualTo(1);
            assertThat(targetEvidence.get(0).path("agentId").asText()).isEqualTo(fixture.agentId());
            assertThat(targetEvidence.get(0).path("generation").asLong()).isEqualTo(1L);

            JsonNode pending = pendingApproval(approvalId, fixture.token());
            assertThat(pending.path("workflowId").asLong()).isEqualTo(fixture.workflowId());
            assertThat(pending.path("runId").asText()).isEqualTo(runId);
            assertThat(pending.path("nodeKey").asText()).isEqualTo(NODE);
            assertThat(pending.path("nodeType").asText()).isEqualTo("script-exec");
            assertThat(pending.path("status").asText()).isEqualTo("pending");
            assertThat(pending.path("detail").isObject()).isTrue();
            assertThat(pending.has("detailJson")).isFalse();

            decide(fixture, approvalId, "approved");
            Job dispatched = fixture.dispatched().poll(30, TimeUnit.SECONDS);
            assertThat(dispatched).as("after-commit dispatch must reach the simulated agent").isNotNull();
            String taskId = dispatched.getTaskId();
            taskService.reportJobResult(new JobResultRequest(dispatched.getId(), fixture.agentId(),
                    2, 0, "simulated chmod help"), fixture.agentId());
            await().atMost(TIMEOUT).untilAsserted(() ->
                    assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo("succeeded"));

            var finished = nodeRuns.findByRunIdAndNodeKeyAndAttempt(runId, NODE, 1).orElseThrow();
            assertThat(nodeRuns.findByRunId(runId)).hasSize(1);
            assertThat(finished.getId()).isEqualTo(waiting.getId());
            assertThat(finished.getStatus()).isEqualTo("succeeded");
            assertThat(finished.getApprovalId()).isEqualTo(approvalId);
            assertThat(finished.getTaskId()).isEqualTo(taskId);
            var consumed = binding(approvalId);
            assertThat(consumed.nodeRunId()).isEqualTo(originalBinding.nodeRunId());
            assertThat(consumed.attempt()).isEqualTo(originalBinding.attempt());
            assertThat(consumed.scriptHmac()).isEqualTo(originalBinding.scriptHmac());
            assertThat(consumed.intentHmac()).isEqualTo(originalBinding.intentHmac());
            assertThat(consumed.targets()).isEqualTo(originalBinding.targets());
            assertThat(consumed.consumedAt()).isNotNull();
            assertThat(consumed.taskId()).isEqualTo(taskId);
            assertThat(bindingCount(runId)).isEqualTo(1L);
            assertThat(tasks.count()).isEqualTo(fixture.tasksBefore() + 1);
            var task = tasks.findById(taskId).orElseThrow();
            assertThat(task.getSource()).isEqualTo("workflow");
            assertThat(task.getCreatedBy()).isEqualTo(fixture.ownerId());
            assertThat(task.getStatus()).isEqualTo(2);
            assertThat(task.getSuccessCount()).isEqualTo(1);
            assertThat(jobs.count()).isEqualTo(fixture.jobsBefore() + 1);
            assertThat(jobs.findByTaskId(taskId)).hasSize(1);
            assertThat(jobs.findById(dispatched.getId()).orElseThrow().getOutput()).isEqualTo("simulated chmod help");
            assertThat(snapshots.findComplete(taskId).orElseThrow().targets())
                    .containsExactly(new TaskTargetSnapshot.Target(fixture.agentId(), 1L));
            assertThat(provenance.findComplete(runId).orElseThrow().taskIds()).containsExactly(taskId);

            // A replay must be rejected explicitly, including when the approval is already consumed.
            mvc.perform(post("/api/v1/workflow/approvals/{id}/decision", approvalId)
                            .header("Authorization", fixture.token()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"approved\",\"comment\":\"duplicate\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("APPROVAL_DECIDED"));
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(fixture.dispatched()).isEmpty();
                assertThat(tasks.count()).isEqualTo(fixture.tasksBefore() + 1);
                assertThat(jobs.count()).isEqualTo(fixture.jobsBefore() + 1);
                assertThat(binding(approvalId)).isEqualTo(consumed);
                verify(agentTransport, times(1)).dispatchJob(eq(fixture.agentId()), any(Job.class), eq(SCRIPT), eq(60));
            });
        } finally {
            cancel(fixture);
        }
    }

    @Test
    void rejectingFirstHighRiskScriptNeverCreatesOrDispatchesATask() throws Exception {
        Fixture fixture = start("rejected");
        try {
            long approvalId = waitingApproval(fixture);
            decide(fixture, approvalId, "rejected");
            await().atMost(TIMEOUT).untilAsserted(() ->
                    assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo("failed"));
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertNoDispatch(fixture);
                assertThat(binding(approvalId).consumedAt()).isNull();
                assertThat(binding(approvalId).taskId()).isNull();
            });
            assertThat(approvals.findById(approvalId).orElseThrow().getStatus()).isEqualTo("rejected");
            assertThat(bindingCount(fixture.runId())).isEqualTo(1L);
            assertThat(nodeRuns.findByRunId(fixture.runId())).hasSize(1);
            assertThat(nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), NODE, 1)
                    .orElseThrow().getTaskId()).isNull();
        } finally {
            cancel(fixture);
        }
    }

    @Test
    void executePermissionRevokedWhileWaitingPreventsApprovedDispatch() throws Exception {
        Fixture fixture = start("revoked");
        try {
            long approvalId = waitingApproval(fixture);
            var original = binding(approvalId);
            acl.change(fixture.ownerId(), fixture.agentId(), "USER", fixture.ownerId().toString(),
                    HostAclService.Permission.EXECUTE, false, 1L);
            assertThat(acl.allowedForGeneration(fixture.ownerId(), fixture.agentId(), 1L,
                    HostAclService.Permission.EXECUTE)).isFalse();
            assertThat(acl.allowedForGeneration(fixture.ownerId(), fixture.agentId(), 1L,
                    HostAclService.Permission.VIEW)).isTrue();
            assertThat(pendingApproval(approvalId, fixture.token()).path("detail").isObject()).isTrue();

            decide(fixture, approvalId, "approved");
            await().atMost(TIMEOUT).untilAsserted(() ->
                    assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo("failed"));
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertRunHasNoDispatch(fixture);
                assertThat(binding(approvalId)).isEqualTo(original);
            });
            assertThat(approvals.findById(approvalId).orElseThrow().getStatus()).isEqualTo("approved");
            assertThat(bindingCount(fixture.runId())).isEqualTo(1L);
        } finally {
            cancel(fixture);
        }
    }

    @Test
    void tamperedBindingTimeoutRestrictsDetailAndForbidsApproval() throws Exception {
        Fixture fixture = start("tampered");
        try {
            long approvalId = waitingApproval(fixture);
            assertThat(pendingApproval(approvalId, fixture.token()).path("detail").isObject()).isTrue();
            assertThat(jdbc.update("UPDATE workflow_dispatch_approval_binding SET dispatch_timeout_sec = ? "
                    + "WHERE approval_id = ?", 61, approvalId)).isEqualTo(1);

            JsonNode restricted = pendingApproval(approvalId, fixture.token());
            var fields = new java.util.ArrayList<String>();
            restricted.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("id", "status", "expiresAt", "canReject", "canApprove");
            assertThat(restricted.path("status").asText()).isEqualTo("pending");
            assertThat(restricted.path("canApprove").asBoolean()).isFalse();
            assertThat(restricted.path("canReject").asBoolean()).isTrue();
            assertThat(restricted.has("detail")).isFalse();
            mvc.perform(post("/api/v1/workflow/approvals/{id}/decision", approvalId)
                            .header("Authorization", fixture.token()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"approved\",\"comment\":\"tampered intent\"}"))
                    .andExpect(status().isForbidden());
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertRunHasNoDispatch(fixture);
                assertThat(approvals.findById(approvalId).orElseThrow().getStatus()).isEqualTo("pending");
                assertThat(binding(approvalId).consumedAt()).isNull();
                assertThat(binding(approvalId).taskId()).isNull();
            });
        } finally {
            cancel(fixture);
        }
    }

    @Test
    void snapshotWriteFailureRollsBackConsumedBindingAndAllDispatchWrites() throws Exception {
        Fixture fixture = start("rollback");
        AtomicReference<String> attemptedTask = new AtomicReference<>();
        AtomicBoolean snapshotWasWritten = new AtomicBoolean();
        AtomicBoolean bindingWasConsumed = new AtomicBoolean();
        TaskTargetSnapshotRepository snapshotSpy = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(snapshots);
        try {
            long approvalId = waitingApproval(fixture);
            var original = binding(approvalId);
            doAnswer(call -> {
                TaskTargetSnapshot snapshot = call.getArgument(0);
                Object result = call.callRealMethod();
                if (snapshot.targets().stream().anyMatch(target -> fixture.agentId().equals(target.agentId()))) {
                    attemptedTask.set(snapshot.taskId());
                    snapshotWasWritten.set(snapshots.findComplete(snapshot.taskId()).isPresent());
                    var inTransaction = bindings.find(approvalId).orElseThrow();
                    bindingWasConsumed.set(inTransaction.consumedAt() != null
                            && snapshot.taskId().equals(inTransaction.taskId()));
                    throw new IllegalStateException("Injected failure after real snapshot write");
                }
                return result;
            }).when(snapshotSpy).create(any(TaskTargetSnapshot.class));

            decide(fixture, approvalId, "approved");
            await().atMost(TIMEOUT).untilAsserted(() ->
                    assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo("failed"));
            assertThat(attemptedTask.get()).as("the real snapshot create was reached").isNotBlank();
            assertThat(snapshotWasWritten.get()).as("snapshot existed before the injected exception").isTrue();
            assertThat(bindingWasConsumed.get()).as("consumption occurred in the failing transaction").isTrue();
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertRunHasNoDispatch(fixture);
                assertThat(binding(approvalId)).isEqualTo(original);
                assertThat(tasks.findById(attemptedTask.get())).isEmpty();
                assertThat(jobs.findByTaskId(attemptedTask.get())).isEmpty();
                assertThat(snapshots.findComplete(attemptedTask.get())).isEmpty();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_target_snapshot WHERE task_id = ?",
                        Long.class, attemptedTask.get())).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_target_snapshot_item WHERE task_id = ?",
                        Long.class, attemptedTask.get())).isZero();
            });
            assertThat(bindingCount(fixture.runId())).isEqualTo(1L);
            assertThat(approvals.findById(approvalId).orElseThrow().getStatus()).isEqualTo("approved");
        } finally {
            try {
                cancel(fixture);
            } finally {
                reset(snapshotSpy);
            }
        }
    }

    @Test
    void watchdogExpiresWaitingDispatchApprovalWithoutCreatingOrDispatchingATask() throws Exception {
        Fixture fixture = start("expired");
        try {
            long approvalId = waitingApproval(fixture);
            var original = binding(approvalId);
            assertThat(jdbc.update("UPDATE workflow_approval SET expires_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(1)), approvalId))
                    .isEqualTo(1);

            // Recovery stays at its default false; dispatch approval expiry must still run.
            watchdog.scanOnce();

            await().atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo("timeout");
                assertThat(nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), NODE, 1)
                        .orElseThrow().getStatus()).isEqualTo("cancelled");
                assertThat(approvals.findById(approvalId).orElseThrow().getStatus()).isEqualTo("timeout");
            });
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertRunHasNoDispatch(fixture);
                assertThat(binding(approvalId)).isEqualTo(original);
                assertThat(binding(approvalId).consumedAt()).isNull();
                assertThat(binding(approvalId).taskId()).isNull();
                assertThat(bindingCount(fixture.runId())).isEqualTo(1L);
            });
        } finally {
            cancel(fixture);
        }
    }

    /** Unique owner and agent also catch orphan task/job writes absent from run provenance. */
    private void assertRunHasNoDispatch(Fixture fixture) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task WHERE created_by = ?",
                Long.class, fixture.ownerId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job WHERE agent_id = ?",
                Long.class, fixture.agentId())).isZero();
        assertThat(provenance.findComplete(fixture.runId()).orElseThrow().taskIds()).isEmpty();
        assertThat(nodeRuns.findByRunId(fixture.runId())).hasSize(1).allSatisfy(node -> {
            assertThat(node.getTaskId()).isNull();
            assertThat(node.getAttempt()).isEqualTo(1);
        });
        assertThat(fixture.dispatched()).isEmpty();
        verify(agentTransport, never()).dispatchJob(eq(fixture.agentId()), any(Job.class), anyString(), anyInt());
    }

    private record Fixture(long workflowId, String runId, Long ownerId, String token, String agentId,
                           LinkedBlockingQueue<Job> dispatched, long tasksBefore, long jobsBefore) { }

    private Fixture start(String name) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User owner = new User();
        owner.setUsername("exact-" + name + "-" + suffix);
        owner.setEmail(owner.getUsername() + "@easyshell.test");
        owner.setPassword(passwords.encode("Integration#2026"));
        owner.setRole("super_admin");
        owner.setStatus(1);
        users.saveAndFlush(owner);
        String token = "Bearer " + jwt.generateAccessToken(owner.getId(), owner.getUsername(), owner.getRole());
        String agentId = "ag-exact-" + suffix;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setHostname("exact-approval-host");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        agents.saveAndFlush(agent);
        identities.initializeExisting(List.of(agentId));
        for (var permission : List.of(HostAclService.Permission.VIEW, HostAclService.Permission.EXECUTE)) {
            acl.change(owner.getId(), agentId, "USER", owner.getId().toString(), permission, true, 1L);
            assertThat(acl.allowedForGeneration(owner.getId(), agentId, 1L, permission)).isTrue();
        }
        var dispatched = new LinkedBlockingQueue<Job>();
        when(agentTransport.dispatchJob(eq(agentId), any(Job.class), eq(SCRIPT), anyInt()))
                .thenAnswer(call -> { dispatched.add(call.getArgument(1)); return true; });
        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip","onRejection":"abort",
                 "variables":{},"nodes":[{"key":"permissions_help","type":"script-exec",
                 "name":"Permissions help","position":{"x":0,"y":0},
                 "config":{"script":"chmod --help","riskPolicy":"strict","timeoutSeconds":60,
                 "targets":{"mode":"explicit","agentIds":["%s"]}}}],"edges":[]}
                """.formatted(agentId);
        long workflowId = data(mvc.perform(post("/api/v1/workflow").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"exact approval " + suffix + "\",\"visibility\":\"private\",\"category\":\"ops\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        JsonNode draft = data(mvc.perform(put("/api/v1/workflow/{id}/versions/draft", workflowId)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":" + definition + ",\"baseVersion\":0,\"expectedAbsent\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String publish = mapper.createObjectNode().put("draftId", draft.path("id").asLong())
                .put("baseVersion", draft.path("baseVersion").asInt())
                .put("definitionSha256", draft.path("definitionSha256").asText()).toString();
        // Publish validates and records HIGH risk; dispatch approval belongs to execution.
        // HTTP 200 alone is insufficient: a risk-blocked response carries body code 449.
        mvc.perform(post("/api/v1/workflow/{id}/publish", workflowId).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(publish))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.estimatedRisk").value("HIGH"));
        long tasksBefore = tasks.count();
        long jobsBefore = jobs.count();
        String runId = data(mvc.perform(post("/api/v1/workflow/{id}/runs", workflowId)
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"input\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString()).path("id").asText();
        return new Fixture(workflowId, runId, owner.getId(), token, agentId, dispatched, tasksBefore, jobsBefore);
    }

    private long waitingApproval(Fixture fixture) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo("waiting");
            var node = nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), NODE, 1).orElseThrow();
            assertThat(node.getStatus()).isEqualTo("waiting");
            assertThat(node.getTaskId()).isNull();
            assertThat(node.getApprovalId()).isNotNull();
            var approval = approvals.findById(node.getApprovalId()).orElseThrow();
            assertThat(approval.getStatus()).isEqualTo("pending");
            assertThat(approval.getNodeType()).isEqualTo("script-exec");
            assertThat(binding(approval.getId()).consumedAt()).isNull();
            assertThat(binding(approval.getId()).taskId()).isNull();
            assertThat(bindingCount(fixture.runId())).isEqualTo(1L);
            assertThat(provenance.findComplete(fixture.runId()).orElseThrow().taskIds()).isEmpty();
            assertNoDispatch(fixture);
        });
        return nodeRuns.findByRunIdAndNodeKeyAndAttempt(fixture.runId(), NODE, 1).orElseThrow().getApprovalId();
    }

    private void assertNoDispatch(Fixture fixture) {
        assertThat(tasks.count()).isEqualTo(fixture.tasksBefore());
        assertThat(jobs.count()).isEqualTo(fixture.jobsBefore());
        assertThat(fixture.dispatched()).isEmpty();
        verify(agentTransport, never()).dispatchJob(eq(fixture.agentId()), any(Job.class), anyString(), anyInt());
    }

    private JsonNode pendingApproval(long approvalId, String token) throws Exception {
        JsonNode pending = data(mvc.perform(get("/api/v1/workflow/approvals/pending").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode approval : pending) {
            if (approval.path("id").asLong() == approvalId) return approval;
        }
        throw new AssertionError("Owner cannot see pending dispatch approval " + approvalId);
    }

    private void decide(Fixture fixture, long approvalId, String decision) throws Exception {
        mvc.perform(post("/api/v1/workflow/approvals/{id}/decision", approvalId)
                        .header("Authorization", fixture.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode().put("decision", decision).put("comment", "exact intent").toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(decision));
        assertThat(approvals.findById(approvalId).orElseThrow().getDecidedBy()).isEqualTo(fixture.ownerId());
    }

    private WorkflowDispatchApprovalBindingRepository.Evidence binding(long approvalId) {
        return new TransactionTemplate(transactions).execute(tx -> bindings.find(approvalId).orElseThrow());
    }

    private long bindingCount(String runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workflow_dispatch_approval_binding b "
                + "JOIN workflow_approval a ON a.id = b.approval_id WHERE a.run_id = ?", Long.class, runId);
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
