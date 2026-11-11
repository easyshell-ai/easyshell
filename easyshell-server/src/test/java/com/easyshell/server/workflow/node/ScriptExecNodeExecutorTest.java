package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunProvenanceRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.DispatchEvidenceVerifier;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.service.HostIdentityService;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.websocket.TaskLogWebSocketHandler;
import com.easyshell.server.workflow.approval.WorkflowApprovalStore;
import com.easyshell.server.workflow.engine.NodeCancelledException;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowEdge;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * script-exec 节点的风险门禁（设计要求：工作流里的脚本执行必须过 {@code createAndDispatch}
 * 这道唯一咽喉；当前未绑定脚本和目标的审批证据暂不能授权派发）。
 *
 * <p>驱动<b>真实</b>的 {@link CommandRiskEngine} 与 {@code TaskServiceImpl}，断言：
 * ① 高风险/封禁脚本在无审批授权时节点失败且<b>不落 task 行</b>；
 * ② 参数值注入在 L1 就被拒，走不到派发；
 * ③ LOW 脚本正常派发（task.source=workflow + execToken + agent_versions 快照）；
 * ④ 临时安全限制下，已审批的 approval 祖先仍不能授权 HIGH 脚本；
 * ⑤ MEDIUM 脚本在派发审批绑定不可用时失败。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=warn"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("script-exec 节点 — 风险门禁与派发授权（真实门禁 + 真实 TaskService）")
class ScriptExecNodeExecutorTest {

    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private WorkflowApprovalRepository approvalRepository;
    @Autowired
    private WorkflowNodeRunRepository nodeRunRepository;
    @Autowired
    private WorkflowRunRepository runRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AuditLogService auditLogService;

    private ObjectMapper objectMapper;
    private ExpressionEvaluator expressionEvaluator;
    private WorkflowApprovalStore approvalStore;
    private WorkflowProperties properties;
    private TaskService taskService;
    private ScriptExecNodeExecutor executor;
    private AgentWebSocketHandler agentHandler;
    private TaskTargetSnapshotRepository targetSnapshots;
    private HostAclService acl;
    private WorkflowRunProvenanceRepository provenanceRepository;
    private WorkflowRunProvenanceService provenanceService;

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS host_identity (agent_id VARCHAR(64) PRIMARY KEY, generation BIGINT NOT NULL, active TINYINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_target_snapshot (task_id VARCHAR(64) PRIMARY KEY, target_count INT NOT NULL, completed TINYINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_target_snapshot_item (task_id VARCHAR(64) NOT NULL, agent_id VARCHAR(64) NOT NULL, generation BIGINT NOT NULL, PRIMARY KEY (task_id, agent_id), FOREIGN KEY (task_id) REFERENCES task_target_snapshot(task_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_run_provenance (run_id VARCHAR(64) PRIMARY KEY, version_id BIGINT NOT NULL, definition_sha256 CHAR(64) NOT NULL, source_kind VARCHAR(16) NOT NULL, task_count INT NOT NULL, completed TINYINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_run_provenance_task (run_id VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL UNIQUE, PRIMARY KEY (run_id, task_id), FOREIGN KEY (run_id) REFERENCES workflow_run_provenance(run_id))");
        jdbc.update("DELETE FROM workflow_run_provenance_task");
        jdbc.update("DELETE FROM workflow_run_provenance");
        jdbc.update("DELETE FROM task_target_snapshot_item");
        jdbc.update("DELETE FROM task_target_snapshot");
        jdbc.update("DELETE FROM host_identity WHERE agent_id = ?", "ag-gate");
        jdbc.update("INSERT INTO host_identity (agent_id, generation, active) VALUES (?, ?, ?)", "ag-gate", 1L, 1);
        targetSnapshots = new TaskTargetSnapshotRepository(jdbc);
        provenanceRepository = new WorkflowRunProvenanceRepository(jdbc);
        jobRepository.deleteAll();
        taskRepository.deleteAll();
        agentRepository.deleteAll();
        approvalRepository.deleteAll();
        nodeRunRepository.deleteAll();

        objectMapper = new ObjectMapper();
        expressionEvaluator = new ExpressionEvaluator(objectMapper);
        approvalStore = new WorkflowApprovalStore(approvalRepository, entityManager, transactionManager);
        properties = new WorkflowProperties();
        properties.getEngine().setDefaultNodeTimeoutSec(300);
        properties.getEngine().setPollIntervalMs(50);

        AgenticConfigService config = mock(AgenticConfigService.class);
        lenient().when(config.getStringSet(anyString(), eq(Set.of()))).thenReturn(Set.of());
        lenient().when(config.getStringList(anyString(), eq(List.of()))).thenReturn(List.of());
        CommandRiskEngine riskEngine = new CommandRiskEngine(config);

        TaskLogWebSocketHandler logHandler = mock(TaskLogWebSocketHandler.class);
        agentHandler = mock(AgentWebSocketHandler.class);
        lenient().when(agentHandler.isAgentConnected(anyString())).thenReturn(true);
        // 模拟 agent 立即回传成功结果（派发回调跑在 afterCommit 里，行已可见）
        lenient().when(agentHandler.dispatchJob(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            String dispatchedAgent = invocation.getArgument(0);
            com.easyshell.server.model.entity.Job dispatched = invocation.getArgument(1);
            // 异步回传结果（不在 afterCommit 里开事务，避免与提交回调纠缠）
            Thread reporter = new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                new TransactionTemplate(transactionManager).execute(tx -> {
                    taskService.reportJobResult(
                            new com.easyshell.server.model.dto.JobResultRequest(dispatched.getId(),
                                    dispatchedAgent, 2, 0, "ok"), dispatchedAgent);
                    return null;
                });
            }, "agent-result-reporter");
            reporter.setDaemon(true);
            reporter.start();
            return true;
        });
        com.easyshell.server.service.impl.TaskCounterService counterService =
                new com.easyshell.server.service.impl.TaskCounterService(taskRepository, jobRepository);
        acl = mock(HostAclService.class);
        provenanceService = org.mockito.Mockito.spy(new WorkflowRunProvenanceService(provenanceRepository,
                mock(WorkflowRunRepository.class), mock(WorkflowVersionRepository.class), targetSnapshots, acl, objectMapper));
        lenient().when(acl.allowedForGeneration(42L, "ag-gate", 1L, HostAclService.Permission.EXECUTE)).thenReturn(true);
        taskService = new com.easyshell.server.service.impl.TaskServiceImpl(
                taskRepository, jobRepository, mock(com.easyshell.server.security.ScriptAccess.class), agentRepository,
                counterService, logHandler, agentHandler, mock(ClusterService.class),
                mock(TagService.class), riskEngine, auditLogService,
                List.of(new com.easyshell.server.workflow.gate.WorkflowApprovalEvidenceVerifier(
                        approvalRepository), mock(DispatchEvidenceVerifier.class)), acl,
                new HostIdentityService(jdbc, agentRepository), targetSnapshots);

        executor = new ScriptExecNodeExecutor(taskService, mock(ClusterService.class),
                mock(TagService.class), agentRepository, jobRepository, taskRepository,
                approvalRepository, nodeRunRepository, runRepository, entityManager, approvalStore, expressionEvaluator, riskEngine,
                new DagValidator(expressionEvaluator), new SensitiveDataFilter(), objectMapper,
                properties, transactionManager);
        executor.setProvenanceDependencies(provenanceService, provenanceRepository, targetSnapshots);

        Agent agent = new Agent();
        agent.setId("ag-gate");
        agent.setHostname("gate-host");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        agentRepository.save(agent);
    }

    private WorkflowNode node(String script, Map<String, Object> extraConfig) {
        WorkflowNode node = new WorkflowNode();
        node.setKey("clean");
        node.setType("script-exec");
        node.setName("clean");
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("script", script);
        config.put("targets", Map.of("mode", "explicit", "agentIds", List.of("ag-gate")));
        config.put("timeoutSeconds", 60);
        if (extraConfig != null) {
            config.putAll(extraConfig);
        }
        node.setConfig(config);
        return node;
    }

    private WorkflowRun newRun() {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(42L);
        run.setStartedAt(LocalDateTime.now());
        runRepository.saveAndFlush(run);
        jdbc.update("INSERT INTO workflow_run_provenance VALUES (?, ?, ?, 'HOST', 0, 1)",
                run.getId(), 1L, "a".repeat(64));
        return run;
    }

    private WorkflowNodeRun newNodeRun(WorkflowRun run, String id) {
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId(id);
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey("clean");
        nodeRun.setNodeType("script-exec");
        nodeRun.setAttempt(1);
        nodeRun.setStatus("running");
        return nodeRunRepository.saveAndFlush(nodeRun);
    }

    private NodeContext context(WorkflowRun run, WorkflowNodeRun nodeRun, WorkflowNode node,
                                VariableStore variables) {
        return context(run, nodeRun, node, variables, new AtomicBoolean());
    }

    @Test
    void committedCancellationPreventsNewTask() {
        WorkflowRun run = newRun();
        WorkflowNodeRun attempt = newNodeRun(run, "wnr-cancel-first");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                assertThat(runRepository.requestCancelAtomically(run.getId())).isEqualTo(1));
        assertThatThrownBy(() -> executor.execute(context(run, attempt, node("df -h", null), new VariableStore())))
                .isInstanceOf(NodeCancelledException.class);
        assertThat(taskRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
        assertThat(nodeRunRepository.findById(attempt.getId()).orElseThrow().getTaskId()).isNull();
        verify(agentHandler, never()).dispatchJob(any(), any(), any(), anyInt());
    }

    @Test
    void cancellationHoldingRunLockBlocksDispatchAndWins() throws Exception {
        WorkflowRun run = newRun();
        WorkflowNodeRun attempt = newNodeRun(run, "wnr-cancel-race");
        NodeContext ctx = context(run, attempt, node("df -h", null), new VariableStore());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancel = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                runRepository.lockById(run.getId()).orElseThrow();
                assertThat(runRepository.requestCancelAtomically(run.getId())).isEqualTo(1);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var dispatch = pool.submit(() -> {
                attempting.countDown();
                return executor.execute(ctx);
            });
            assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> dispatch.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            cancel.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> dispatch.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(NodeCancelledException.class);
            assertThat(taskRepository.count()).isZero();
            assertThat(jobRepository.count()).isZero();
            assertThat(nodeRunRepository.findById(attempt.getId()).orElseThrow().getTaskId()).isNull();
            verify(agentHandler, never()).dispatchJob(any(), any(), any(), anyInt());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void legacyAiScriptRequiresHumanDominatingApprovalAndNoRaw() {
        WorkflowRun run = newRun();
        WorkflowNode ai = new WorkflowNode();
        ai.setKey("ai");
        ai.setType("classifier");
        WorkflowNode gate = new WorkflowNode();
        gate.setKey("gate");
        gate.setType("approval");
        WorkflowNode script = node("echo {{X:raw}}", Map.of("rawParams", List.of("X"),
                "parameters", Map.of("X", "constant")));
        WorkflowEdge first = new WorkflowEdge();
        first.setFrom("ai"); first.setTo("gate");
        WorkflowEdge second = new WorkflowEdge();
        second.setFrom("gate"); second.setTo("clean");
        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(List.of(ai, gate, script));
        def.setEdges(List.of(first, second));
        VariableStore vars = new VariableStore();
        NodeContext ctx = new NodeContext(run, newNodeRun(run, "wnr-ai"), script, def, vars,
                Executors.newFixedThreadPool(2), new AtomicBoolean(), new AtomicBoolean(), false, e -> {}, vars);
        assertThatThrownBy(() -> executor.execute(ctx)).isInstanceOf(NodeFailedException.class)
                .satisfies(e -> assertThat(((NodeFailedException) e).getCode()).isEqualTo("AI_OUTPUT_RAW_FORBIDDEN"));
        script.setConfig(Map.of("script", "df -h", "targets", Map.of("mode", "explicit", "agentIds", List.of("ag-gate"))));
        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId(run.getId()); approval.setNodeKey("gate"); approval.setNodeType("approval");
        approval.setTitle("确认"); approval.setStatus("approved");
        approval.setExpiresAt(LocalDateTime.now().plusHours(1));
        approval = approvalRepository.saveAndFlush(approval);
        assertThatThrownBy(() -> executor.execute(ctx)).isInstanceOf(NodeFailedException.class)
                .satisfies(e -> assertThat(((NodeFailedException) e).getCode()).isEqualTo("AI_APPROVAL_REQUIRED"));
        approval.setDecidedBy(9L);
        approvalRepository.saveAndFlush(approval);
        WorkflowEdge bypass = new WorkflowEdge();
        bypass.setFrom("ai"); bypass.setTo("clean");
        def.setEdges(List.of(first, second, bypass));
        assertThatThrownBy(() -> executor.execute(ctx)).isInstanceOf(NodeFailedException.class)
                .satisfies(e -> assertThat(((NodeFailedException) e).getCode()).isEqualTo("AI_APPROVAL_REQUIRED"));
        assertThat(taskRepository.count()).isZero();
    }

    private NodeContext context(WorkflowRun run, WorkflowNodeRun nodeRun, WorkflowNode node,
                                VariableStore variables, AtomicBoolean cancelled) {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(List.of(node));
        return new NodeContext(run, nodeRun, node, def, variables, Executors.newFixedThreadPool(2),
                cancelled, new AtomicBoolean(), false, event -> {
        }, variables);
    }

    @Test
    @DisplayName("高风险脚本在工作流里被拒：RISK_BLOCKED 且不落 task/job 行")
    void highRiskScriptIsRejectedInWorkflow() {
        WorkflowRun run = newRun();
        assertThatThrownBy(() -> executor.execute(context(run, newNodeRun(run, "wnr-gate-1"),
                node("systemctl restart nginx", null), new VariableStore())))
                .isInstanceOf(NodeFailedException.class)
                .satisfies(e -> assertThat(((NodeFailedException) e).getCode()).isEqualTo("RISK_BLOCKED"));
        assertThat(taskRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
    }

    @Test
    @DisplayName("封禁脚本在工作流里被拒（rm -rf /：人工审批之外的任何路径都不可豁免）")
    void bannedScriptIsRejectedInWorkflow() {
        WorkflowRun run = newRun();
        assertThatThrownBy(() -> executor.execute(context(run, newNodeRun(run, "wnr-gate-2"),
                node("rm -rf /", null), new VariableStore())))
                .isInstanceOf(NodeFailedException.class)
                .satisfies(e -> assertThat(((NodeFailedException) e).getCode()).isEqualTo("RISK_BLOCKED"));
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("参数值注入在 L1 即被拒：$(reboot) 走不到派发，也没有 task 行")
    void parameterInjectionRejectedBeforeDispatch() {
        WorkflowRun run = newRun();
        assertThatThrownBy(() -> executor.execute(context(run, newNodeRun(run, "wnr-gate-3"),
                node("echo {{MSG}}", Map.of("parameters", Map.of("MSG", "$(reboot)"))),
                new VariableStore())))
                .isInstanceOf(NodeFailedException.class)
                .satisfies(e -> assertThat(((NodeFailedException) e).getCode()).isEqualTo("PARAM_UNSAFE"));
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("LOW 脚本正常派发：落 task/job 行，execToken + agent_versions + source=workflow")
    void lowRiskScriptIsDispatched() {
        WorkflowRun run = newRun();
        WorkflowNodeRun nodeRun = newNodeRun(run, "wnr-gate-4");
        VariableStore variables = new VariableStore();

        NodeOutcome outcome = executor.execute(context(run, nodeRun,
                node("df -h", Map.of("outputVar", "disk")), variables));

        assertThat(outcome).isEqualTo(NodeOutcome.SUCCEEDED);
        assertThat(nodeRun.getTaskId()).startsWith("task_");
        assertThat(nodeRun.getInputJson()).contains("\"parameters\"");
        assertThat(nodeRun.getAgentVersions()).contains("ag-gate");
        Task task = taskRepository.findById(nodeRun.getTaskId()).orElseThrow();
        assertThat(task.getSource()).isEqualTo("workflow");
        assertThat(task.getName()).contains(run.getId() + ":clean:1");   // execToken（幂等③）
        assertThat(jobRepository.findByTaskId(task.getId())).hasSize(1);
        assertThat(targetSnapshots.findComplete(task.getId()).orElseThrow().targets())
                .containsExactly(new com.easyshell.server.model.dto.TaskTargetSnapshot.Target("ag-gate", 1L));
        assertThat(variables.resolve("nodes.clean.output")).isPresent();
        assertThat(nodeRunRepository.findById(nodeRun.getId()).orElseThrow().getTaskId()).isEqualTo(task.getId());
        assertThat(provenanceRepository.findComplete(run.getId()).orElseThrow().taskIds()).containsExactly(task.getId());
    }

    @Test
    void associationFailureRollsBackTaskSnapshotBindingAndDispatch() {
        WorkflowRun run = newRun();
        WorkflowNodeRun nodeRun = newNodeRun(run, "wnr-provenance-failure");
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("injected association failure");
        }).when(provenanceService).associateTask(eq(run.getId()), anyString());

        assertThatThrownBy(() -> executor.execute(context(run, nodeRun, node("df -h", null), new VariableStore())))
                .isInstanceOf(IllegalStateException.class).hasMessage("injected association failure");
        assertThat(taskRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_target_snapshot", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_target_snapshot_item", Integer.class)).isZero();
        assertThat(nodeRunRepository.findById(nodeRun.getId()).orElseThrow().getTaskId()).isNull();
        assertThat(provenanceRepository.findComplete(run.getId()).orElseThrow().taskIds()).isEmpty();
        verify(agentHandler, never()).dispatchJob(any(), any(), any(), anyInt());
    }

    @Test
    void recoveryRejectsMissingHistoricalProvenanceWithoutBackfill() {
        WorkflowRun run = newRun();
        WorkflowNodeRun nodeRun = newNodeRun(run, "wnr-missing-provenance");
        executor.execute(context(run, nodeRun, node("df -h", null), new VariableStore()));
        jdbc.update("DELETE FROM workflow_run_provenance_task WHERE run_id = ?", run.getId());
        jdbc.update("DELETE FROM workflow_run_provenance WHERE run_id = ?", run.getId());
        assertThatThrownBy(() -> executor.execute(context(run, newNodeRunReference(run, nodeRun.getId()),
                node("df -h", null), new VariableStore()))).isInstanceOf(IllegalStateException.class);
        assertThat(provenanceRepository.findComplete(run.getId())).isEmpty();
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    void lowRiskScriptCannotDispatchWithoutHostExecuteGrant() {
        org.mockito.Mockito.when(acl.allowedForGeneration(42L, "ag-gate", 1L,
                HostAclService.Permission.EXECUTE)).thenReturn(false);
        WorkflowRun run = newRun();
        assertThatThrownBy(() -> executor.execute(context(run, newNodeRun(run, "wnr-no-grant"),
                node("df -h", null), new VariableStore())))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        assertThat(taskRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
        verify(agentHandler, never()).dispatchJob(any(), any(), any(), anyInt());
    }

    @Test
    void recoveryReusesBoundTaskBeforeValidatingChangedConfiguration() {
        WorkflowRun run = newRun();
        WorkflowNodeRun nodeRun = newNodeRun(run, "wnr-recovery");
        executor.execute(context(run, nodeRun, node("df -h", null), new VariableStore()));
        String taskId = nodeRun.getTaskId();
        long approvals = approvalRepository.count();
        WorkflowNodeRun stale = newNodeRunReference(run, nodeRun.getId());
        assertThat(executor.execute(context(run, stale, node("", null), new VariableStore())))
                .isEqualTo(NodeOutcome.SUCCEEDED);
        assertThat(stale.getTaskId()).isEqualTo(taskId);
        assertThat(taskRepository.count()).isEqualTo(1);
        assertThat(approvalRepository.count()).isEqualTo(approvals);
    }

    @Test
    void concurrentExecutionsBindOnlyOneTask() throws Exception {
        WorkflowRun run = newRun();
        WorkflowNodeRun original = newNodeRun(run, "wnr-concurrent");
        var pool = Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var one = pool.submit(() -> {
                start.await();
                return executor.execute(context(run, original, node("df -h", null), new VariableStore()));
            });
            var two = pool.submit(() -> {
                start.await();
                return executor.execute(context(run, newNodeRunReference(run, original.getId()),
                        node("df -h", null), new VariableStore()));
            });
            start.countDown();
            assertThat(one.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(NodeOutcome.SUCCEEDED);
            assertThat(two.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(NodeOutcome.SUCCEEDED);
            assertThat(taskRepository.count()).isEqualTo(1);
            assertThat(jobRepository.count()).isEqualTo(1);
            assertThat(nodeRunRepository.findById(original.getId()).orElseThrow().getTaskId()).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    private WorkflowNodeRun newNodeRunReference(WorkflowRun run, String id) {
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId(id);
        row.setRunId(run.getId());
        row.setNodeKey("clean");
        row.setAttempt(1);
        return row;
    }

    @Test
    void sourceUpdateDoesNotOverwriteOfflineTaskTerminalCounters() {
        Agent agent = agentRepository.findById("ag-gate").orElseThrow();
        agent.setStatus(0);
        agentRepository.saveAndFlush(agent);
        org.mockito.Mockito.when(agentHandler.isAgentConnected("ag-gate")).thenReturn(false);
        WorkflowRun run = newRun();
        WorkflowNodeRun nodeRun = newNodeRun(run, "wnr-offline");
        assertThatThrownBy(() -> executor.execute(context(run, nodeRun, node("df -h", null), new VariableStore())))
                .isInstanceOf(NodeFailedException.class);
        Task task = taskRepository.findById(nodeRun.getTaskId()).orElseThrow();
        assertThat(task.getSource()).isEqualTo("workflow");
        assertThat(task.getFailedCount()).isEqualTo(1);
        assertThat(task.getStatus()).isNotEqualTo(1);
    }

    @Test
    void conflictMarkerDoesNotOverwriteTerminalAttempt() {
        WorkflowRun run = newRun();
        WorkflowNodeRun nodeRun = newNodeRun(run, "wnr-terminal");
        nodeRun.setStatus("succeeded");
        nodeRunRepository.saveAndFlush(nodeRun);
        Integer updated = new TransactionTemplate(transactionManager).execute(tx ->
                nodeRunRepository.markFailedIfPresent(run.getId(), "clean", 1, "conflict", LocalDateTime.now()));
        assertThat(updated).isZero();
        assertThat(nodeRunRepository.findById(nodeRun.getId()).orElseThrow().getStatus()).isEqualTo("succeeded");
    }

    @Test
    @DisplayName("临时安全限制：未绑定脚本和目标的祖先审批不能授权 HIGH 脚本，门禁返回 449")
    void approvedAncestorWithoutDispatchBindingRejectsHighRiskScript() {
        WorkflowRun run = newRun();
        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId(run.getId());
        approval.setNodeKey("gate");
        approval.setNodeType("approval");
        approval.setTitle("确认");
        approval.setStatus("approved");
        approval.setDecidedBy(9L);
        approval.setDecidedAt(LocalDateTime.now());
        approval.setExpiresAt(LocalDateTime.now().plusHours(1));
        approvalRepository.saveAndFlush(approval);

        WorkflowNodeRun nodeRun = newNodeRun(run, "wnr-gate-5");
        WorkflowNode clean = node("systemctl restart nginx", null);
        WorkflowNode gate = new WorkflowNode();
        gate.setKey("gate");
        gate.setType("approval");
        gate.setConfig(new java.util.LinkedHashMap<>(Map.of("title", "确认")));
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId("e1");
        edge.setFrom("gate");
        edge.setTo("clean");
        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(List.of(gate, clean));
        def.setEdges(List.of(edge));

        NodeContext ctx = new NodeContext(run, nodeRun, clean, def, new VariableStore(),
                Executors.newFixedThreadPool(2), new AtomicBoolean(), new AtomicBoolean(), false,
                event -> {
                }, new VariableStore());

        assertThatThrownBy(() -> executor.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(449));
        assertThat(nodeRun.getTaskId()).isNull();
        assertThat(taskRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
        verify(agentHandler, never()).dispatchJob(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("MEDIUM 脚本 + allow_medium：派发审批绑定不可用时失败，不创建审批")
    void mediumScriptFailsWithoutDispatchApprovalBinding() {
        WorkflowRun run = newRun();
        WorkflowNodeRun nodeRun = newNodeRun(run, "wnr-gate-6");
        long existingApprovals = approvalRepository.count();

        assertThatThrownBy(() -> executor.execute(context(run, nodeRun,
                node("find /var/log -name '*.log' -delete", Map.of("riskPolicy", "allow_medium")),
                new VariableStore())))
                .isInstanceOf(NodeFailedException.class)
                .satisfies(e -> assertThat(((NodeFailedException) e).getCode())
                        .isEqualTo("WORKFLOW_DISPATCH_APPROVAL_UNAVAILABLE"));

        assertThat(nodeRun.getApprovalId()).isNull();
        assertThat(approvalRepository.count()).isEqualTo(existingApprovals);
        assertThat(taskRepository.count()).isZero();
        verify(agentHandler, never()).dispatchJob(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("MEDIUM 脚本 + 默认 block：节点失败（riskPolicy 三态里最严的默认）")
    void mediumScriptBlockedByDefaultPolicy() {
        WorkflowRun run = newRun();
        assertThatThrownBy(() -> executor.execute(context(run, newNodeRun(run, "wnr-gate-7"),
                node("find /var/log -name '*.log' -delete", null), new VariableStore())))
                .isInstanceOf(NodeFailedException.class)
                .satisfies(e -> assertThat(((NodeFailedException) e).getCode()).isEqualTo("RISK_BLOCKED"));
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("targets.mode=variable 引用不存在的 agent：执行前校验失败（host-list 规则）")
    void variableTargetsValidatedBeforeExecution() {
        WorkflowRun run = newRun();
        VariableStore variables = new VariableStore();
        variables.putNodeOutput("disk", "disk", Map.of("hostList", List.of("ag-missing")));

        assertThatThrownBy(() -> executor.execute(context(run, newNodeRun(run, "wnr-gate-8"),
                node("df -h", Map.of("targets", Map.of("mode", "variable",
                        "variableRef", "nodes['disk'].output.hostList"))), variables)))
                .isInstanceOf(NodeFailedException.class)
                .satisfies(e -> assertThat(((NodeFailedException) e).getCode())
                        .isEqualTo("WORKFLOW_TYPE_MISMATCH"));
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("取消传播：轮询中取消标志已翻转 → NodeCancelledException（不再等 task）")
    void cancellationPropagatesDuringPolling() {
        WorkflowRun run = newRun();
        WorkflowNodeRun nodeRun = newNodeRun(run, "wnr-gate-9");
        VariableStore variables = new VariableStore();
        AtomicBoolean cancelled = new AtomicBoolean();

        // 取消标志先翻掉：LOW 脚本照常派发，但轮询第一轮就该退出
        cancelled.set(true);

        assertThatThrownBy(() -> executor.execute(context(run, nodeRun,
                node("df -h", null), variables, cancelled)))
                .isInstanceOf(NodeCancelledException.class);
    }
}
