package com.easyshell.server.workflow;

import com.easyshell.server.ai.channel.ChannelMessageRouter;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.entity.Workflow;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.model.entity.WorkflowVersion;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.DispatchEvidenceVerifier;
import com.easyshell.server.service.TagService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.easyshell.server.websocket.TaskLogWebSocketHandler;
import com.easyshell.server.workflow.approval.WorkflowApprovalStore;
import com.easyshell.server.workflow.approval.WorkflowResumeService;
import com.easyshell.server.workflow.engine.AttemptAllocator;
import com.easyshell.server.workflow.engine.AttemptConflictException;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeExecutorRegistry;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.engine.WorkflowEvent;
import com.easyshell.server.workflow.engine.WorkflowWatchdog;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowEdge;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.node.ApprovalNodeExecutor;
import com.easyshell.server.workflow.node.ConditionNodeExecutor;
import com.easyshell.server.workflow.node.NotifyNodeExecutor;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.easyshell.server.workflow.service.WorkflowRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 执行内核集成测试（真实 H2，MODE=MySQL；{@code @Transactional(NOT_SUPPORTED)} 因为
 * 引擎跑在自己的线程池里、attempt 行用 REQUIRES_NEW 独立事务，测试线程必须看到真实提交）。
 *
 * <p>覆盖设计要求的最小集：并发执行、幂等重投、失败三策略、审批挂起/恢复（含 10s 扫描兜底）、
 * 取消（含"取消后不再接受 job 回传"）、超时看门狗、attempt 抢占冲突。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=warn"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("工作流执行引擎 — 并发/幂等/失败策略/审批/取消/看门狗（真实 H2）")
class WorkflowEngineH2Test {

    @Autowired
    private WorkflowRepository workflowRepository;
    @Autowired
    private WorkflowVersionRepository versionRepository;
    @Autowired
    private WorkflowRunRepository runRepository;
    @Autowired
    private WorkflowNodeRunRepository nodeRunRepository;
    @Autowired
    private WorkflowApprovalRepository approvalRepository;
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AuditLogService auditLogService;

    private ObjectMapper objectMapper;
    private ExpressionEvaluator expressionEvaluator;
    private DagValidator dagValidator;
    private AttemptAllocator attemptAllocator;
    private WorkflowApprovalStore approvalStore;
    private WorkflowProperties properties;
    private ExecutorService enginePool;
    private ThreadPoolTaskScheduler retryScheduler;
    private WorkflowEngine engine;
    private WorkflowRunService runService;
    private WorkflowResumeService resumeService;
    private WorkflowWatchdog watchdog;
    private ChannelMessageRouter channelMessageRouter;

    private final List<WorkflowEvent> events = new CopyOnWriteArrayList<>();
    private final List<StubExecutor> stubs = new CopyOnWriteArrayList<>();

    private Long workflowId;
    private Long versionId;

    /** 可编程桩执行器：按 NodeType 分派，行为由测试注入 */
    static class StubExecutor implements NodeExecutor {
        private final NodeType type;
        private volatile Function<NodeContext, NodeOutcome> behavior = ctx -> NodeOutcome.SUCCEEDED;
        final AtomicInteger calls = new AtomicInteger();

        StubExecutor(NodeType type) {
            this.type = type;
        }

        @Override
        public NodeType supports() {
            return type;
        }

        @Override
        public NodeOutcome execute(NodeContext ctx) {
            calls.incrementAndGet();
            return behavior.apply(ctx);
        }
    }

    /** 抢占必然冲突的分配器：用于确定性地验证引擎对 AttemptConflictException 的反应 */
    static class ConflictingAllocator extends AttemptAllocator {
        ConflictingAllocator(WorkflowNodeRunRepository repo, PlatformTransactionManager tx) {
            super(repo, tx);
        }

        @Override
        public WorkflowNodeRun startAttempt(String runId, String nodeKey, String nodeType) {
            throw new AttemptConflictException(runId, nodeKey, 1);
        }
    }

    @BeforeEach
    void setUp() {
        approvalRepository.deleteAll();
        nodeRunRepository.deleteAll();
        runRepository.deleteAll();
        versionRepository.deleteAll();
        workflowRepository.deleteAll();
        jobRepository.deleteAll();
        taskRepository.deleteAll();
        agentRepository.deleteAll();

        objectMapper = new ObjectMapper();
        expressionEvaluator = new ExpressionEvaluator(objectMapper);
        dagValidator = new DagValidator(expressionEvaluator);
        attemptAllocator = new AttemptAllocator(nodeRunRepository, transactionManager);
        approvalStore = new WorkflowApprovalStore(approvalRepository, transactionManager);
        properties = new WorkflowProperties();
        properties.getEngine().setDefaultRunTimeoutSec(3600);
        properties.getEngine().setDefaultNodeTimeoutSec(300);
        properties.getEngine().setMaxConcurrentNodes(5);
        properties.getEngine().setMinTriggerIntervalSec(0);
        enginePool = Executors.newFixedThreadPool(5, runnable -> {
            Thread thread = new Thread(runnable, "workflow-engine-test");
            thread.setDaemon(true);
            return thread;
        });
        retryScheduler = new ThreadPoolTaskScheduler();
        retryScheduler.setPoolSize(2);
        retryScheduler.setThreadNamePrefix("workflow-scheduler-test");
        retryScheduler.initialize();

        channelMessageRouter = mock(ChannelMessageRouter.class);
        Consumer<WorkflowEvent> sink = events::add;

        engine = new WorkflowEngine(runRepository, nodeRunRepository, approvalRepository, versionRepository,
                jobRepository, registry(), dagValidator, expressionEvaluator, attemptAllocator,
                auditLogService, new SensitiveDataFilter(), Optional.of(sink), enginePool, retryScheduler,
                properties, objectMapper, transactionManager);
        runService = new WorkflowRunService(workflowRepository, versionRepository, runRepository,
                nodeRunRepository, approvalRepository, engine, auditLogService, properties, objectMapper,
                transactionManager);
        resumeService = new WorkflowResumeService(approvalStore, runRepository, nodeRunRepository, engine,
                auditLogService, properties, transactionManager);
        watchdog = new WorkflowWatchdog(runRepository, nodeRunRepository, approvalRepository,
                versionRepository, approvalStore, engine, auditLogService, properties, objectMapper,
                transactionManager);

        Workflow workflow = new Workflow();
        workflow.setName("engine-test");
        workflow.setCreatedBy(42L);
        workflowId = workflowRepository.saveAndFlush(workflow).getId();
    }

    @AfterEach
    void tearDown() {
        enginePool.shutdownNow();
        retryScheduler.shutdown();
    }

    /** 注册表：5 类执行器，其中 condition/notify/approval 用真实实现 */
    private NodeExecutorRegistry registry() {
        stubs.clear();
        StubExecutor scriptExec = new StubExecutor(NodeType.SCRIPT_EXEC);
        StubExecutor wait = new StubExecutor(NodeType.WAIT);
        stubs.add(scriptExec);
        stubs.add(wait);
        return new NodeExecutorRegistry(List.of(
                scriptExec,
                new ConditionNodeExecutor(expressionEvaluator),
                new NotifyNodeExecutor(channelMessageRouter, expressionEvaluator, new SensitiveDataFilter()),
                new ApprovalNodeExecutor(approvalStore, expressionEvaluator, properties),
                wait));
    }

    private StubExecutor stub(NodeType type) {
        return stubs.stream().filter(s -> s.supports() == type).findFirst().orElseThrow();
    }

    private WorkflowNode node(String key, String type, Map<String, Object> config) {
        WorkflowNode node = new WorkflowNode();
        node.setKey(key);
        node.setType(type);
        node.setName(key);
        node.setConfig(new java.util.LinkedHashMap<>(config));
        return node;
    }

    private WorkflowEdge edge(String from, String to) {
        return edge(from, to, null);
    }

    private WorkflowEdge edge(String from, String to, String condition) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(from + "->" + to);
        edge.setFrom(from);
        edge.setTo(to);
        edge.setCondition(condition);
        return edge;
    }

    private Long publishDefinition(String definitionJson) {
        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowId(workflowId);
        version.setVersion(1);
        version.setDefinitionJson(definitionJson);
        version.setDefinitionSha256("a".repeat(64));
        version.setIsValid(true);
        version.setEstimatedRisk("LOW");
        Long id = versionRepository.saveAndFlush(version).getId();
        this.versionId = id;
        return id;
    }

    /** 线性三节点：check(script-exec) -> gate(condition) -> notify(notify) */
    private String linearDefinition(String defaultOnUpstreamFailure) {
        return """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"%s","onRejection":"abort",
                 "variables":{"threshold":{"type":"number","required":false,"default":90}},
                 "nodes":[
                   {"key":"check","type":"script-exec","name":"check","config":{"script":"df -h","targets":{"mode":"explicit","agentIds":["ag-1"]},"outputVar":"disk"}},
                   {"key":"gate","type":"condition","name":"gate","config":{"expression":"nodes.check.output.maxPcent > run.input.threshold"}},
                   {"key":"notify","type":"notify","name":"notify","config":{"channels":["telegram"],"messageTemplate":"done"}}],
                 "edges":[
                   {"id":"e1","from":"check","to":"gate"},
                   {"id":"e2","from":"gate","to":"notify","condition":"nodes.gate.output.result == true"}]}
                """.formatted(defaultOnUpstreamFailure);
    }

    /** 并行两分支：start -> gate(condition) -> {a, b} */
    private String fanOutDefinition() {
        return """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip",
                 "variables":{"threshold":{"type":"number"}},
                 "nodes":[
                   {"key":"start","type":"script-exec","name":"start","config":{"script":"echo go","targets":{"mode":"explicit","agentIds":["ag-1"]}}},
                   {"key":"gate","type":"condition","name":"gate","config":{"expression":"run.input.threshold > 0"}},
                   {"key":"a","type":"script-exec","name":"a","config":{"script":"echo a","targets":{"mode":"explicit","agentIds":["ag-1"]}}},
                   {"key":"b","type":"script-exec","name":"b","config":{"script":"echo b","targets":{"mode":"explicit","agentIds":["ag-1"]}}}],
                 "edges":[
                   {"id":"e1","from":"start","to":"gate"},
                   {"id":"e2","from":"gate","to":"a"},
                   {"id":"e3","from":"gate","to":"b"}]}
                """;
    }

    /** 审批链：check -> approve(approval) -> notify */
    private String approvalDefinition() {
        return """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip","onRejection":"abort",
                 "nodes":[
                   {"key":"check","type":"script-exec","name":"check","config":{"script":"df -h","targets":{"mode":"explicit","agentIds":["ag-1"]}}},
                   {"key":"approve","type":"approval","name":"approve","config":{"title":"确认执行","timeoutSec":600}},
                   {"key":"notify","type":"notify","name":"notify","config":{"channels":["telegram"],"messageTemplate":"done"}}],
                 "edges":[
                   {"id":"e1","from":"check","to":"approve"},
                   {"id":"e2","from":"approve","to":"notify"}]}
                """;
    }

    private WorkflowRun startRun(String definitionJson, Map<String, Object> input) {
        publishDefinition(definitionJson);
        return runService.startRun(workflowId, versionId, input, 42L, null, "manual");
    }

    private WorkflowNodeRun nodeRun(String runId, String nodeKey) {
        return nodeRunRepository.findByRunId(runId).stream()
                .filter(r -> r.getNodeKey().equals(nodeKey))
                .max(Comparator.comparing(WorkflowNodeRun::getAttempt))
                .orElseThrow();
    }

    private String statusOf(String runId, String nodeKey) {
        List<WorkflowNodeRun> rows = nodeRunRepository.findByRunId(runId).stream()
                .filter(r -> r.getNodeKey().equals(nodeKey)).toList();
        return rows.isEmpty() ? "" : nodeRun(runId, nodeKey).getStatus();
    }

    /** 等待 run 到达指定状态（waiting 是审批挂起的准终态，测试里也要能等） */
    private WorkflowRun awaitStatus(String runId, String... statuses) {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            WorkflowRun run = runRepository.findById(runId).orElseThrow();
            if (List.of(statuses).contains(run.getStatus())) {
                return run;
            }
            sleepQuietly(20);
        }
        throw new AssertionError("run 未在 20s 内到达 " + List.of(statuses) + ": " + runId
                + "（当前 " + runRepository.findById(runId).map(WorkflowRun::getStatus).orElse("?") + "）");
    }

    private WorkflowRun awaitTerminal(String runId) {
        return awaitStatus(runId, "succeeded", "failed", "cancelled", "timeout");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 等待某个节点的最新 attempt 行到达指定状态 */
    private WorkflowNodeRun awaitNodeStatus(String runId, String nodeKey, String... statuses) {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            List<WorkflowNodeRun> rows = nodeRunRepository.findByRunId(runId).stream()
                    .filter(r -> r.getNodeKey().equals(nodeKey)).toList();
            if (!rows.isEmpty()) {
                WorkflowNodeRun latest = rows.stream()
                        .max(Comparator.comparing(WorkflowNodeRun::getAttempt)).orElseThrow();
                if (List.of(statuses).contains(latest.getStatus())) {
                    return latest;
                }
            }
            sleepQuietly(20);
        }
        throw new AssertionError("节点未在 20s 内到达 " + List.of(statuses) + ": " + nodeKey + " " + dumpRun());
    }

    /** 失败信息里带上全部 run 与节点行状态（Gradle 默认不捕获测试 stdout，只能走断言描述） */
    private String dumpRun() {
        StringBuilder sb = new StringBuilder("状态转储: ");
        for (WorkflowRun run : runRepository.findAll()) {
            sb.append('[').append(run.getId()).append('=').append(run.getStatus())
                    .append(run.getErrorMessage() == null ? "" : "(" + run.getErrorMessage() + ")").append("] ");
        }
        for (WorkflowNodeRun row : nodeRunRepository.findAll()) {
            sb.append(row.getRunId(), 4, 12).append('/').append(row.getNodeKey())
                    .append('#').append(row.getAttempt()).append('=').append(row.getStatus())
                    .append(row.getErrorMessage() == null ? "" : "(" + row.getErrorMessage() + ")")
                    .append(row.getWarningMessage() == null ? "" : "[warn:" + row.getWarningMessage() + "]")
                    .append(' ');
        }
        return sb.toString();
    }

    /** 任意定义发布（测试自造节点用） */
    private Long publishNodes(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setSchemaVersion(1);
        def.setDefaultOnUpstreamFailure("skip");
        def.setOnRejection("abort");
        def.setNodes(nodes);
        def.setEdges(edges);
        try {
            return publishDefinition(objectMapper.writeValueAsString(def));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("并发执行：同一调度波的两个节点真正并行（CountDownLatch 证明）")
    void concurrentWaveExecution() throws Exception {
        CountDownLatch bothArrived = new CountDownLatch(2);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            // 只有同一调度波的 a/b 参与并发证明：start 在更早一波，进闩会误伤
            if (!Set.of("a", "b").contains(ctx.node().getKey())) {
                return NodeOutcome.SUCCEEDED;
            }
            concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(concurrent.get(), Math::max);
            bothArrived.countDown();
            try {
                // 两个节点必须都到达这里才能继续：串行执行会超时
                assertThat(bothArrived.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            return NodeOutcome.SUCCEEDED;
        };

        WorkflowRun run = startRun(fanOutDefinition(), Map.of("threshold", 1));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(maxConcurrent.get()).isEqualTo(2);
        assertThat(statusOf(run.getId(), "a")).as(dumpRun()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "b")).as(dumpRun()).isEqualTo("succeeded");
        assertThat(stub(NodeType.SCRIPT_EXEC).calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("变量传递：上游 output 被下游表达式读到（标量通道端到端）")
    void variablePassingBetweenNodes() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(),
                    Map.of("maxPcent", 93.2));
            return NodeOutcome.SUCCEEDED;
        };

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "gate")).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "notify")).isEqualTo("succeeded");
        assertThat(nodeRun(run.getId(), "gate").getOutputJson()).contains("\"result\":true");
        WorkflowRun reloaded = runRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getVariablesJson()).contains("nodes.check.output");
        assertThat(reloaded.getHeartbeatAt()).isNotNull();
    }

    @Test
    @DisplayName("幂等重投：同一 run 并发 drive 两次，节点只执行一次（per-run 锁）")
    void concurrentDriveIsIdempotent() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            executions.incrementAndGet();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return NodeOutcome.SUCCEEDED;
        };

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        // 第一个 drive 已在引擎池里跑；再直接调 drive() 两次（模拟消息重投）
        engine.drive(run);
        engine.drive(run);
        release.countDown();

        WorkflowRun terminal = awaitTerminal(run.getId());
        assertThat(terminal.getStatus()).isEqualTo("succeeded");
        assertThat(executions.get()).isEqualTo(1);
        assertThat(nodeRunRepository.findByRunId(run.getId())).hasSize(3);
    }

    @Test
    @DisplayName("幂等：run 已终态后再次 drive 不产生任何新 attempt")
    void driveAfterTerminalIsNoop() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> NodeOutcome.SUCCEEDED;
        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        awaitTerminal(run.getId());

        int before = nodeRunRepository.findByRunId(run.getId()).size();
        engine.drive(run);
        assertThat(nodeRunRepository.findByRunId(run.getId())).hasSize(before);
        assertThat(events).anySatisfy(e -> assertThat(e.type()).isEqualTo("RUN_SUCCEEDED"));
    }

    @Test
    @DisplayName("attempt 抢占冲突：引擎捕获 AttemptConflictException 后终止 run 并记审计 ERROR")
    void attemptConflictTerminatesRun() {
        // uk_node_run 本身挡住重复 INSERT 的行为由 WorkflowRepositoryTest 覆盖；
        // 这里用必然冲突的分配器确定性地验证引擎的兜底路径（§3.6）
        WorkflowEngine conflictingEngine = new WorkflowEngine(runRepository, nodeRunRepository,
                approvalRepository, versionRepository, jobRepository, registry(), dagValidator,
                expressionEvaluator, new ConflictingAllocator(nodeRunRepository, transactionManager),
                auditLogService, new SensitiveDataFilter(), Optional.of(events::add), enginePool,
                retryScheduler, properties, objectMapper, transactionManager);
        WorkflowRunService conflictingRunService = new WorkflowRunService(workflowRepository,
                versionRepository, runRepository, nodeRunRepository, approvalRepository,
                conflictingEngine, auditLogService, properties, objectMapper, transactionManager);

        publishDefinition(linearDefinition("skip"));
        WorkflowRun run = conflictingRunService.startRun(workflowId, versionId, Map.of("threshold", 90),
                42L, null, "manual");
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).isEqualTo("failed");
        assertThat(terminal.getErrorMessage()).contains("attempt_conflict");
        verify(auditLogService).logSync(eq(42L), any(), eq("WORKFLOW_ATTEMPT_CONFLICT"),
                eq("workflow_node_run"), anyString(), anyString(), any(), eq("error"));
    }

    private Function<NodeContext, NodeOutcome> failCheckThenSucceed() {
        return ctx -> {
            if ("check".equals(ctx.node().getKey())) {
                throw new NodeFailedException("TASK_FAILED", "模拟失败");
            }
            return NodeOutcome.SUCCEEDED;
        };
    }

    @Test
    @DisplayName("失败策略 skip（默认）：上游失败 → 下游 skipped(reason=upstream_failed)，run=failed")
    void failurePolicySkip() {
        stub(NodeType.SCRIPT_EXEC).behavior = failCheckThenSucceed();

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "check")).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "gate")).as(dumpRun()).isEqualTo("skipped");
        assertThat(nodeRun(run.getId(), "gate").getErrorMessage()).isEqualTo("upstream_failed");
        assertThat(statusOf(run.getId(), "notify")).isEqualTo("skipped");
    }

    @Test
    @DisplayName("失败策略 partial：上游失败也执行本节点，边条件不可求值走 false 分支")
    void failurePolicyPartial() {
        stub(NodeType.SCRIPT_EXEC).behavior = failCheckThenSucceed();

        WorkflowRun run = startRun(linearDefinition("partial"), Map.of("threshold", 90));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "check")).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "gate")).as(dumpRun()).isEqualTo("succeeded");
        assertThat(nodeRun(run.getId(), "gate").getWarningMessage()).contains("条件求值失败");
        assertThat(statusOf(run.getId(), "notify")).isEqualTo("skipped");
    }

    @Test
    @DisplayName("失败策略 abort：未开始节点批量 cancelled(reason=upstream_failed)")
    void failurePolicyAbort() {
        stub(NodeType.SCRIPT_EXEC).behavior = failCheckThenSucceed();

        WorkflowRun run = startRun(linearDefinition("abort"), Map.of("threshold", 90));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "check")).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "gate")).as(dumpRun()).isEqualTo("cancelled");
        assertThat(nodeRun(run.getId(), "gate").getErrorMessage()).isEqualTo("upstream_failed");
        assertThat(statusOf(run.getId(), "notify")).isEqualTo("cancelled");
        assertThat(terminal.getErrorMessage()).contains("node_failed:check");
    }

    @Test
    @DisplayName("审批挂起：run=waiting 且不占线程；decision 后恢复并跑完")
    void approvalSuspendAndResume() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> NodeOutcome.SUCCEEDED;
        WorkflowRun run = startRun(approvalDefinition(), Map.of());

        WorkflowRun waiting = awaitStatus(run.getId(), "waiting");
        assertThat(waiting.getStatus()).isEqualTo("waiting");
        assertThat(statusOf(run.getId(), "approve")).isEqualTo("waiting");
        assertThat(statusOf(run.getId(), "notify")).isEmpty();
        WorkflowApproval approval = approvalRepository.findByRunIdAndNodeKey(run.getId(), "approve")
                .orElseThrow();
        assertThat(approval.getStatus()).isEqualTo("pending");
        assertThat(approval.getTitle()).isEqualTo("确认执行");
        assertThat(nodeRun(run.getId(), "approve").getApprovalId()).isEqualTo(approval.getId());
        assertThat(events).anySatisfy(e -> assertThat(e.type()).isEqualTo("RUN_WAITING"));

        resumeService.decide(approval.getId(), "approved", 9L, "同意");
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "approve")).isEqualTo("succeeded");
        assertThat(nodeRun(run.getId(), "approve").getOutputJson()).contains("\"decision\":\"approved\"");
        assertThat(statusOf(run.getId(), "notify")).isEqualTo("succeeded");
        WorkflowApproval decided = approvalRepository.findById(approval.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo("approved");
        assertThat(decided.getDecidedBy()).isEqualTo(9L);
        verify(auditLogService).logSync(eq(9L), any(), eq("WORKFLOW_APPROVE"), eq("workflow_approval"),
                eq(String.valueOf(approval.getId())), anyString(), any(), eq("success"));
    }

    @Test
    @DisplayName("审批被拒 + onRejection=abort：下游级联 cancelled，run=failed")
    void approvalRejectionCascades() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> NodeOutcome.SUCCEEDED;
        WorkflowRun run = startRun(approvalDefinition(), Map.of());
        awaitStatus(run.getId(), "waiting");
        WorkflowApproval approval = approvalRepository.findByRunIdAndNodeKey(run.getId(), "approve")
                .orElseThrow();

        resumeService.decide(approval.getId(), "rejected", 9L, "不合规");
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "approve")).isEqualTo("failed");
        assertThat(nodeRun(run.getId(), "approve").getErrorMessage()).contains("approval_rejected");
        assertThat(statusOf(run.getId(), "notify")).as(dumpRun()).isEqualTo("cancelled");
        assertThat(terminal.getErrorMessage()).contains("approval_rejected");
    }

    @Test
    @DisplayName("重复决策：第二条 decide 抛 409 APPROVAL_DECIDED（version 乐观锁）")
    void duplicateDecisionRejected() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> NodeOutcome.SUCCEEDED;
        WorkflowRun run = startRun(approvalDefinition(), Map.of());
        awaitStatus(run.getId(), "waiting");
        WorkflowApproval approval = approvalRepository.findByRunIdAndNodeKey(run.getId(), "approve")
                .orElseThrow();

        resumeService.decide(approval.getId(), "approved", 9L, "同意");
        assertThatThrownBy(() -> resumeService.decide(approval.getId(), "rejected", 10L, "改主意"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    @Test
    @DisplayName("10s 扫描兜底：不走事件、直接改库后由扫描器恢复（§3.8 唤醒路径③）")
    void wakeupScannerRecoversMissedEvent() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> NodeOutcome.SUCCEEDED;
        WorkflowRun run = startRun(approvalDefinition(), Map.of());
        awaitStatus(run.getId(), "waiting");
        WorkflowApproval approval = approvalRepository.findByRunIdAndNodeKey(run.getId(), "approve")
                .orElseThrow();

        // 绕过 decide() 的事件路径，直接改库（模拟"事件丢了"）
        assertThat(approvalStore.decide(approval.getId(), "approved", 9L, "同意")).isTrue();

        resumeService.resumeIfReady(run.getId());
        WorkflowRun terminal = awaitTerminal(run.getId());
        assertThat(terminal.getStatus()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "notify")).isEqualTo("succeeded");
    }

    @Test
    @DisplayName("还有未决策审批时扫描器不唤醒（漏一个都整体放弃）")
    void scannerWaitsForAllDecisions() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> NodeOutcome.SUCCEEDED;
        WorkflowRun run = startRun(approvalDefinition(), Map.of());
        awaitStatus(run.getId(), "waiting");

        resumeService.resumeIfReady(run.getId());
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(statusOf(run.getId(), "notify")).isEmpty();
    }

    @Test
    @DisplayName("取消：执行中节点收到取消标志并退出，run=cancelled，未开始节点 cancelled")
    void cancelDuringExecution() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Boolean> sawCancel = new AtomicReference<>(false);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sawCancel.set(ctx.isCancelled());
            finished.countDown();
            return NodeOutcome.SUCCEEDED;
        };

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        engine.requestCancel(run.getId(), 42L);
        release.countDown();
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        // finishNode 在桩返回之后才写库：等到 check 行到达终态再断言，避免读到中间态
        awaitNodeStatus(run.getId(), "check", "succeeded", "failed", "skipped", "cancelled", "timeout");

        WorkflowRun terminal = awaitTerminal(run.getId());
        assertThat(terminal.getStatus()).isEqualTo("cancelled");
        assertThat(sawCancel.get()).isTrue();
        assertThat(statusOf(run.getId(), "check")).as(dumpRun()).isEqualTo("cancelled");
        assertThat(statusOf(run.getId(), "gate")).isEqualTo("cancelled");
        assertThat(terminal.getErrorMessage()).contains("user_cancelled");
        verify(auditLogService).logSync(eq(42L), any(), eq("WORKFLOW_RUN_CANCEL"), eq("workflow_run"),
                eq(run.getId()), contains("task_ids="), any(), eq("success"));
        // 幂等：重复取消不再写第二次终态
        engine.requestCancel(run.getId(), 42L);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("取消 vs 恢复竞态：先取消后决策 → 决策影响 0 行，run 保持 cancelled")
    void cancelBeatsResumeDeterministically() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> NodeOutcome.SUCCEEDED;
        WorkflowRun run = startRun(approvalDefinition(), Map.of());
        awaitStatus(run.getId(), "waiting");
        WorkflowApproval approval = approvalRepository.findByRunIdAndNodeKey(run.getId(), "approve")
                .orElseThrow();

        engine.requestCancel(run.getId(), 42L);
        assertThat(approvalRepository.findById(approval.getId()).orElseThrow().getStatus())
                .isEqualTo("cancelled");

        assertThatThrownBy(() -> resumeService.decide(approval.getId(), "approved", 9L, "迟到的同意"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("cancelled");
        assertThat(statusOf(run.getId(), "notify")).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("看门狗：run 级超时置 timeout，未开始节点取消")
    void watchdogTimesOutRun() throws Exception {
        properties.getEngine().setDefaultRunTimeoutSec(60);
        CountDownLatch nodeStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            nodeStarted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return NodeOutcome.SUCCEEDED;
        };

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        assertThat(nodeStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // 手动把 started_at 提前到超时线之外（等价于已经跑了 120s）
        WorkflowRun reloaded = runRepository.findById(run.getId()).orElseThrow();
        reloaded.setStartedAt(LocalDateTime.now().minusSeconds(120));
        runRepository.saveAndFlush(reloaded);

        watchdog.scanOnce();
        release.countDown();
        awaitNodeStatus(run.getId(), "check", "succeeded", "failed", "skipped", "cancelled", "timeout");

        WorkflowRun terminal = awaitTerminal(run.getId());
        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("timeout");
        assertThat(terminal.getErrorMessage()).contains("run_timeout");
        verify(auditLogService).logSync(eq(42L), any(), eq("WORKFLOW_RUN_TIMEOUT"), eq("workflow_run"),
                eq(run.getId()), anyString(), any(), eq("warning"));
    }

    @Test
    @DisplayName("看门狗：审批 expires_at 到点置 timeout 并按 onTimeout 收尾")
    void watchdogExpiresApproval() {
        properties.getEngine().setDefaultRunTimeoutSec(3600);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> NodeOutcome.SUCCEEDED;
        publishNodes(List.of(
                node("check", "script-exec", Map.of("script", "df -h",
                        "targets", Map.of("mode", "explicit", "agentIds", List.of("ag-1")))),
                node("approve", "approval", Map.of("title", "确认", "timeoutSec", 600, "onTimeout", "reject")),
                node("notify", "notify", Map.of("channels", List.of("telegram"), "messageTemplate", "done"))),
                List.of(edge("check", "approve"), edge("approve", "notify")));

        WorkflowRun run = runService.startRun(workflowId, versionId, Map.of(), 42L, null, "manual");
        assertThat(awaitStatus(run.getId(), "waiting").getStatus()).isEqualTo("waiting");

        WorkflowApproval approval = approvalRepository.findByRunIdAndNodeKey(run.getId(), "approve")
                .orElseThrow();
        approval.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        approvalRepository.saveAndFlush(approval);

        watchdog.scanOnce();

        WorkflowRun terminal = awaitTerminal(run.getId());
        assertThat(terminal.getStatus()).isEqualTo("failed");
        assertThat(approvalRepository.findById(approval.getId()).orElseThrow().getStatus())
                .isEqualTo("timeout");
        assertThat(nodeRun(run.getId(), "approve").getErrorMessage()).contains("approval_timeout");
    }

    @Test
    @DisplayName("看门狗：僵尸 attempt（线程死亡后没人收尾的 running 行）置 timeout")
    void watchdogTimesOutStaleAttempt() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> NodeOutcome.SUCCEEDED;
        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        awaitTerminal(run.getId());

        // 造一个"执行线程已死"的 running attempt：started_at 提前到超时线之外。
        // 节点键必须存在于定义里（看门狗要按节点 timeoutSec 判定），这里复用 check
        String runId = run.getId();
        WorkflowNodeRun zombie = attemptAllocator.startAttempt(runId, "check", "script-exec");
        zombie.setStartedAt(LocalDateTime.now().minusSeconds(600));
        nodeRunRepository.saveAndFlush(zombie);
        WorkflowNodeRun persisted = nodeRunRepository.findById(zombie.getId()).orElseThrow();
        assertThat(persisted.getStartedAt()).isBefore(LocalDateTime.now().minusSeconds(500));
        WorkflowRun reloaded = runRepository.findById(runId).orElseThrow();
        reloaded.setStatus("running");
        reloaded.setStartedAt(LocalDateTime.now().minusSeconds(10));
        reloaded.setHeartbeatAt(LocalDateTime.now().minusSeconds(10));
        runRepository.saveAndFlush(reloaded);

        watchdog.scanOnce();

        WorkflowNodeRun timedOut = nodeRunRepository.findByRunId(runId).stream()
                .filter(r -> r.getNodeKey().equals("check"))
                .max(Comparator.comparing(WorkflowNodeRun::getAttempt))
                .orElseThrow();
        assertThat(timedOut.getAttempt()).isEqualTo(2);
        assertThat(timedOut.getStatus()).as(dumpRun()).isEqualTo("timeout");
        // 看门狗把"为什么超时"写进 error_message（异常终态的原因字段）
        assertThat(timedOut.getErrorMessage()).contains("看门狗兜底");
    }

    @Test
    @DisplayName("取消后不再接受 job 回传：job 置 5，agent 迟到结果被丢弃且不计数")
    void cancelledRunRejectsLateJobResult() {
        com.easyshell.server.ai.config.AgenticConfigService config =
                mock(com.easyshell.server.ai.config.AgenticConfigService.class);
        lenient().when(config.getStringSet(anyString(), eq(Set.of()))).thenReturn(Set.of());
        lenient().when(config.getStringList(anyString(), eq(List.of()))).thenReturn(List.of());
        com.easyshell.server.ai.risk.CommandRiskEngine riskEngine =
                new com.easyshell.server.ai.risk.CommandRiskEngine(config);
        TaskLogWebSocketHandler logHandler = mock(TaskLogWebSocketHandler.class);
        AgentWebSocketHandler agentHandler = mock(AgentWebSocketHandler.class);
        lenient().when(agentHandler.isAgentConnected(anyString())).thenReturn(true);
        lenient().when(agentHandler.dispatchJob(any(), any(), any(), anyInt())).thenReturn(true);
        com.easyshell.server.service.impl.TaskCounterService counterService =
                new com.easyshell.server.service.impl.TaskCounterService(taskRepository, jobRepository);
        TaskService taskService = new com.easyshell.server.service.impl.TaskServiceImpl(
                taskRepository, jobRepository, mock(ScriptRepository.class), agentRepository, counterService,
                logHandler, agentHandler, mock(ClusterService.class), mock(TagService.class), riskEngine,
                auditLogService, List.of(mock(DispatchEvidenceVerifier.class)));

        Agent agent = new Agent();
        agent.setId("ag-cancel");
        agent.setHostname("cancel-host");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        agentRepository.save(agent);

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        awaitTerminal(run.getId());
        WorkflowNodeRun row = attemptAllocator.startAttempt(run.getId(), "late", "script-exec");
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("late-task");
        request.setScriptContent("echo late");
        request.setAgentIds(List.of("ag-cancel"));
        request.setTimeoutSeconds(60);
        // createAndDispatch 里要注册 afterCommit 回调，必须放在事务里跑
        TransactionTemplate dispatchTx = new TransactionTemplate(transactionManager);
        Task task = dispatchTx.execute(tx -> taskService.createAndDispatch(request, 42L));
        row.setTaskId(task.getId());
        nodeRunRepository.saveAndFlush(row);

        WorkflowRun reloaded = runRepository.findById(run.getId()).orElseThrow();
        reloaded.setStatus("running");
        reloaded.setStartedAt(LocalDateTime.now().minusSeconds(5));
        reloaded.setHeartbeatAt(LocalDateTime.now());
        runRepository.saveAndFlush(reloaded);

        Job job = jobRepository.findByTaskId(task.getId()).get(0);

        engine.requestCancel(run.getId(), 42L);

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(5);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("cancelled");

        // agent 跑完后回传结果：被 job 终态检查丢弃，任务计数不变
        taskService.reportJobResult(new JobResultRequest(job.getId(), "ag-cancel", 2, 0, "late output"),
                "ag-cancel");
        Job afterReport = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(afterReport.getStatus()).isEqualTo(5);
        assertThat(afterReport.getOutput()).doesNotContain("late output");
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getSuccessCount()).isZero();
    }
}
