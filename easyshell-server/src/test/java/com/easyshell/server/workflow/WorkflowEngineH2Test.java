package com.easyshell.server.workflow;

import com.easyshell.server.ai.channel.ChannelMessageRouter;
import com.easyshell.server.ai.channel.PushOutcome;
import com.easyshell.server.ai.security.AiQuotaService;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.ai.service.ChatModelFactory;
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
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
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
import com.easyshell.server.workflow.approval.WorkflowApprovalAttemptBinding;
import com.easyshell.server.workflow.approval.WorkflowResumeService;
import com.easyshell.server.workflow.engine.AttemptAllocator;
import com.easyshell.server.workflow.engine.AttemptConflictException;
import com.easyshell.server.workflow.engine.DagScheduler;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeExecutorRegistry;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.engine.WorkflowEvent;
import com.easyshell.server.workflow.engine.WorkflowPendingWakeupScanner;
import com.easyshell.server.workflow.engine.WorkflowWatchdog;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowEdge;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.node.ApprovalNodeExecutor;
import com.easyshell.server.workflow.node.ClassifierNodeExecutor;
import com.easyshell.server.workflow.node.CodeNodeExecutor;
import com.easyshell.server.workflow.node.ConditionNodeExecutor;
import com.easyshell.server.workflow.node.EndNodeExecutor;
import com.easyshell.server.workflow.node.LlmNodeExecutor;
import com.easyshell.server.workflow.node.NotifyNodeExecutor;
import com.easyshell.server.workflow.node.StartNodeExecutor;
import com.easyshell.server.workflow.node.ScriptExecNodeExecutor;
import com.easyshell.server.workflow.node.TransformNodeExecutor;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.easyshell.server.workflow.service.WorkflowRunService;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
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
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行内核集成测试（真实 H2，MODE=MySQL；{@code @Transactional(NOT_SUPPORTED)} 因为
 * 引擎跑在自己的线程池里、attempt 行用 REQUIRES_NEW 独立事务，测试线程必须看到真实提交）。
 *
 * <p>覆盖设计要求的最小集：并发执行、幂等重投、失败三策略、审批挂起/恢复（含 10s 扫描兜底）、
 * 取消（含"取消后不再接受 job 回传"）、超时看门狗、attempt 抢占冲突。</p>
 */
@DataJpaTest
@Import({WorkflowApprovalStore.class, WorkflowApprovalAttemptBinding.class})
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
    @Autowired
    private jakarta.persistence.EntityManager entityManager;
    @Autowired
    private WorkflowApprovalAttemptBinding approvalAttemptBinding;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AuditLogService auditLogService;

    /**
     * 取消路径标记：让仓储装饰代理只闩取消线程的写入，不闩引擎线程的同键写入
     */
    private static final ThreadLocal<Boolean> CANCEL_PATH = new ThreadLocal<>();

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
    private WorkflowRunProvenanceService provenanceService;
    private WorkflowResumeService resumeService;
    private WorkflowWatchdog watchdog;
    private ChannelMessageRouter channelMessageRouter;
    private ChatModelFactory chatModelFactory;
    private ChatModel chatModel;
    private AiQuotaService aiQuotaService;
    private ExecutorService workflowAiPool;
    private CodeNodeExecutor codeExecutor;

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
        ConflictingAllocator(WorkflowNodeRunRepository repo, WorkflowRunRepository runs, PlatformTransactionManager tx) {
            super(repo, runs, tx);
        }

        @Override
        public WorkflowNodeRun startAttempt(String runId, String nodeKey, String nodeType, int expectedPrevious) {
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
        attemptAllocator = new AttemptAllocator(nodeRunRepository, runRepository, transactionManager);
        approvalStore = new WorkflowApprovalStore(approvalRepository, entityManager, transactionManager);
        properties = new WorkflowProperties();
        // These legacy watchdog/scanner scenarios explicitly exercise opt-in recovery behavior.
        properties.getEngine().setAutomaticRecoveryEnabled(true);
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

        chatModelFactory = mock(ChatModelFactory.class);
        chatModel = mock(ChatModel.class);
        aiQuotaService = mock(AiQuotaService.class);
        lenient().when(chatModelFactory.getChatModel(nullable(String.class), nullable(String.class),
                nullable(Double.class))).thenReturn(chatModel);
        workflowAiPool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "workflow-ai-test");
            thread.setDaemon(true);
            return thread;
        });
        codeExecutor = new CodeNodeExecutor(expressionEvaluator, objectMapper, new SensitiveDataFilter());

        channelMessageRouter = mock(ChannelMessageRouter.class);
        // notify 节点的推送结果：默认按"已交付"打桩（P-3 之后 pushMessage 返回 PushOutcome，
        // 不打桩会拿到 null；需要"渠道不可用"的用例在方法内重新打桩）
        lenient().when(channelMessageRouter.pushMessage(anyString(), anyString()))
                .thenReturn(PushOutcome.DELIVERED);
        Consumer<WorkflowEvent> sink = events::add;

        provenanceService = mock(WorkflowRunProvenanceService.class);
        lenient().when(provenanceService.verifiedIdentity(any())).thenReturn(WorkflowRunProvenanceService.VerifiedIdentity.LEGACY);
        engine = new WorkflowEngine(runRepository, nodeRunRepository, approvalRepository, versionRepository, workflowRepository,
                jobRepository, registry(), dagValidator, expressionEvaluator, attemptAllocator,
                auditLogService, new SensitiveDataFilter(), Optional.of(sink), enginePool, retryScheduler,
                properties, objectMapper, transactionManager);
        runService = new WorkflowRunService(workflowRepository, versionRepository, runRepository,
                nodeRunRepository, approvalRepository, engine, auditLogService, properties, objectMapper,
                transactionManager);
        ReflectionTestUtils.setField(runService, "provenanceService", provenanceService);
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
        workflowAiPool.shutdownNow();
        codeExecutor.close();
        retryScheduler.shutdown();
    }

    /** 注册表：本轮用到的执行器（start/transform/condition/end/notify/approval + 两个桩） */
    private NodeExecutorRegistry registry() {
        stubs.clear();
        StubExecutor scriptExec = new StubExecutor(NodeType.SCRIPT_EXEC);
        StubExecutor wait = new StubExecutor(NodeType.WAIT);
        stubs.add(scriptExec);
        stubs.add(wait);
        return new NodeExecutorRegistry(List.of(
                scriptExec,
                new StartNodeExecutor(),
                new TransformNodeExecutor(expressionEvaluator),
                new ConditionNodeExecutor(expressionEvaluator),
                new EndNodeExecutor(expressionEvaluator),
                new NotifyNodeExecutor(channelMessageRouter, expressionEvaluator, new SensitiveDataFilter()),
                new ApprovalNodeExecutor(approvalStore, new com.easyshell.server.workflow.approval.WorkflowApprovalSuspensionService(
                        approvalAttemptBinding, transactionManager), expressionEvaluator, properties, provenanceService),
                new LlmNodeExecutor(chatModelFactory, new SensitiveDataFilter(), aiQuotaService,
                        expressionEvaluator, workflowAiPool, properties, objectMapper),
                new ClassifierNodeExecutor(chatModelFactory, new SensitiveDataFilter(), aiQuotaService,
                        expressionEvaluator, workflowAiPool, properties, objectMapper),
                codeExecutor,
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
                   {"id":"e2","from":"gate","to":"a","sourceHandle":"true"},
                   {"id":"e3","from":"gate","to":"b","sourceHandle":"true"}]}
                """;
    }

    /**
     * 工作流 2.0 主链路：start -> transform -> condition -> {true/false branch} -> end。
     * 两条分支最终都汇到唯一 end；end 输出只取 condition 结果与 transform 映射值。
     */
    private String startTransformConditionEndDefinition() {
        return """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip","onRejection":"abort",
                 "variables":{"threshold":{"type":"number","required":true}},
                 "nodes":[
                   {"key":"begin","type":"start","name":"begin","config":{}},
                   {"key":"mapping","type":"transform","name":"mapping","config":{"mappings":[
                     {"name":"usage","value":"${nodes.begin.output.threshold}"},
                     {"name":"label","value":"使用率 ${nodes.begin.output.threshold}%"}]}},
                   {"key":"gate","type":"condition","name":"gate","config":{"expression":"nodes.mapping.output.usage > 90"}},
                   {"key":"ok_branch","type":"transform","name":"ok","config":{"mappings":[
                     {"name":"branch","value":"true"}]}},
                   {"key":"bad_branch","type":"transform","name":"bad","config":{"mappings":[
                     {"name":"branch","value":"false"}]}},
                   {"key":"finish","type":"end","name":"finish","config":{"outputs":[
                     {"name":"ok","value":"${nodes.gate.output.result}"},
                     {"name":"usage","value":"${nodes.mapping.output.usage}"},
                     {"name":"summary","value":"阈值 ${nodes.mapping.output.usage}"}]}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"mapping"},
                   {"id":"e2","from":"mapping","to":"gate"},
                   {"id":"e3","from":"gate","to":"ok_branch","sourceHandle":"true"},
                   {"id":"e4","from":"gate","to":"bad_branch","sourceHandle":"false"},
                   {"id":"e5","from":"ok_branch","to":"finish"},
                   {"id":"e6","from":"bad_branch","to":"finish"}]}
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
        assertThat(reloaded.getVariablesJson()).contains("\"run.workflowName\":\"engine-test\"");
        assertThat(reloaded.getHeartbeatAt()).isNotNull();
    }

    @Test
    @DisplayName("工作流 2.0：start -> transform -> condition -> end，true 只走 IF，run.output=end 映射")
    void startTransformConditionEnd_trueBranch() throws Exception {
        WorkflowRun run = startRun(startTransformConditionEndDefinition(), Map.of("threshold", 95));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "begin")).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "mapping")).isEqualTo("succeeded");
        assertThat(nodeRun(run.getId(), "mapping").getOutputJson()).contains("\"usage\":95");
        assertThat(nodeRun(run.getId(), "gate").getOutputJson()).contains("\"result\":true");
        assertThat(statusOf(run.getId(), "ok_branch")).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "bad_branch")).as(dumpRun()).isEqualTo("skipped");
        assertThat(statusOf(run.getId(), "finish")).isEqualTo("succeeded");

        // run.output 只应是 end 的映射，不是各节点输出聚合
        @SuppressWarnings("unchecked")
        Map<String, Object> output = objectMapper.readValue(terminal.getOutputJson(), Map.class);
        assertThat(output.get("ok")).isEqualTo(true);
        assertThat(((Number) output.get("usage")).doubleValue()).isEqualTo(95.0);
        assertThat(output.get("summary")).isEqualTo("阈值 95");
        assertThat(output).doesNotContainKey("mapping");
        assertThat(output).doesNotContainKey("begin");
    }

    @Test
    @DisplayName("工作流 2.0：condition=false 时只走 ELSE，end 仍然产出完整 run.output")
    void startTransformConditionEnd_falseBranch() throws Exception {
        WorkflowRun run = startRun(startTransformConditionEndDefinition(), Map.of("threshold", 5));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(nodeRun(run.getId(), "gate").getOutputJson()).contains("\"result\":false");
        assertThat(statusOf(run.getId(), "ok_branch")).as(dumpRun()).isEqualTo("skipped");
        assertThat(statusOf(run.getId(), "bad_branch")).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "finish")).isEqualTo("succeeded");

        @SuppressWarnings("unchecked")
        Map<String, Object> output = objectMapper.readValue(terminal.getOutputJson(), Map.class);
        assertThat(output.get("ok")).isEqualTo(false);
        assertThat(((Number) output.get("usage")).doubleValue()).isEqualTo(5.0);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** start -> classifier -> {disk, network, default} -> end（工作流 2.1 动态 handle） */
    private String classifierDefinition() {
        return """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip","onRejection":"abort",
                 "variables":{"question":{"type":"string","required":true}},
                 "nodes":[
                   {"key":"begin","type":"start","name":"begin","config":{}},
                   {"key":"classify","type":"classifier","name":"classify","config":{
                     "input":"${nodes.begin.output.question}",
                     "classes":[{"name":"disk","description":"磁盘/容量"},{"name":"network","description":"网络/端口"}]}},
                   {"key":"disk_branch","type":"transform","name":"disk","config":{"mappings":[
                     {"name":"branch","value":"disk"}]}},
                   {"key":"network_branch","type":"transform","name":"network","config":{"mappings":[
                     {"name":"branch","value":"network"}]}},
                   {"key":"default_branch","type":"transform","name":"default","config":{"mappings":[
                     {"name":"branch","value":"default"}]}},
                   {"key":"finish","type":"end","name":"finish","config":{"outputs":[
                     {"name":"branch","value":"${nodes.disk_branch.output.branch}"}]}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"classify"},
                   {"id":"e2","from":"classify","to":"disk_branch","sourceHandle":"disk"},
                   {"id":"e3","from":"classify","to":"network_branch","sourceHandle":"network"},
                   {"id":"e4","from":"classify","to":"default_branch","sourceHandle":"default"},
                   {"id":"e5","from":"disk_branch","to":"finish"},
                   {"id":"e6","from":"network_branch","to":"finish"},
                   {"id":"e7","from":"default_branch","to":"finish"}]}
                """;
    }

    private String llmCodeDefinition() {
        return """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip","onRejection":"abort",
                 "variables":{"question":{"type":"string","required":true}},
                 "nodes":[
                   {"key":"begin","type":"start","name":"begin","config":{}},
                   {"key":"llm","type":"llm","name":"llm","config":{
                     "userPrompt":"分析 ${nodes.begin.output.question}",
                     "outputMode":"json","maxInputChars":1000}},
                   {"key":"code","type":"code","name":"code","config":{
                     "language":"javascript",
                     "code":"function main(input) { return { score: input.data.score, doubled: input.data.score * 2 }; }",
                     "inputs":{"data":"${nodes.llm.output.json}"},"timeoutSec":5}},
                   {"key":"finish","type":"end","name":"finish","config":{"outputs":[
                     {"name":"score","value":"${nodes.code.output.score}"}]}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"llm"},
                   {"id":"e2","from":"llm","to":"code"},
                   {"id":"e3","from":"code","to":"finish"}]}
                """;
    }

    @Test
    @DisplayName("工作流 2.1：classifier 按 output.class 精确路由，其他分支 skipped")
    void classifierRoutesByClass() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("{\"class\":\"disk\",\"reason\":\"磁盘容量告警\"}"));

        WorkflowRun run = startRun(classifierDefinition(), Map.of("question", "磁盘满了怎么办"));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "classify")).as(dumpRun()).isEqualTo("succeeded");
        assertThat(nodeRun(run.getId(), "classify").getOutputJson())
                .contains("\"class\":\"disk\"").contains("\"reason\"");
        assertThat(statusOf(run.getId(), "disk_branch")).as(dumpRun()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "network_branch")).as(dumpRun()).isEqualTo("skipped");
        assertThat(statusOf(run.getId(), "default_branch")).as(dumpRun()).isEqualTo("skipped");
        assertThat(statusOf(run.getId(), "finish")).isEqualTo("succeeded");
    }

    @Test
    @DisplayName("工作流 2.1：旧定义到达 code 节点时安全拒绝执行")
    void llmAndCodeProduceOutputs() throws Exception {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(chatResponse("```json\n{\"summary\":\"disk ok\",\"score\":90}\n```"));

        WorkflowRun run = startRun(llmCodeDefinition(), Map.of("question", "检查磁盘"));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("failed");
        assertThat(nodeRun(run.getId(), "llm").getOutputJson())
                .contains("\"structured\":true").contains("\"score\":90");
        assertThat(nodeRun(run.getId(), "code").getOutputJson()).isNull();
        assertThat(nodeRun(run.getId(), "code").getErrorMessage()).contains("CODE_RUNTIME_UNAVAILABLE");
    }

    @Test
    @DisplayName("queued run 泵回后 run.input.* 与立即启动一致（队列丢入参回归）")
    void queuedRunSeedsTriggerInputs() {
        properties.getEngine().setMaxConcurrentRuns(1);
        Long publishedVersionId = publishDefinition(startTransformConditionEndDefinition());

        WorkflowRun queued = new WorkflowRun();
        queued.setId("wfr_queued_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        queued.setWorkflowId(workflowId);
        queued.setVersionId(publishedVersionId);
        queued.setVersionNo(1);
        queued.setTriggerType("manual");
        queued.setStatus("queued");
        queued.setStartedBy(42L);
        queued.setInputJson("{\"threshold\":95}");
        runRepository.saveAndFlush(queued);

        WorkflowPendingWakeupScanner scanner = new WorkflowPendingWakeupScanner(
                runRepository, engine, auditLogService, properties, transactionManager);
        scanner.scanOnce();

        WorkflowRun terminal = awaitTerminal(queued.getId());
        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(nodeRun(queued.getId(), "gate").getOutputJson()).contains("\"result\":true");
        assertThat(nodeRun(queued.getId(), "mapping").getOutputJson()).contains("\"usage\":95");
    }

    @Test
    @DisplayName("MF-13 / P-1：触发入参与节点渲染参数都快照落库（两级 input_json 均非空）")
    void inputJsonSnapshotsArePersistedAtRunAndNodeLevel() {
        // 执行体在 attempt 行 INSERT 之后才回填 inputJson（真实 ScriptExecNodeExecutor 的时序），
        // 这里用桩复现该时序：只有 finishAttemptAtomically 带上 inputJson，库里的列才非空
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            ctx.nodeRun().setInputJson("{\"parameters\":{\"UPSTREAM\":\"PASS-42\"},\"rawParams\":[]}");
            ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(),
                    Map.of("maxPcent", 93.2));
            return NodeOutcome.SUCCEEDED;
        };

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        awaitTerminal(run.getId());

        // run 级：触发入参全量快照（§5.2 / 可复现性），规范化后的形态
        WorkflowRun reloaded = runRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getInputJson()).as(dumpRun()).isNotNull();
        assertThat(reloaded.getInputJson()).contains("\"threshold\"");

        // 节点级：渲染后的 parameters Map + rawParams 声明（MF-13），不含渲染后脚本全文
        WorkflowNodeRun check = nodeRun(run.getId(), "check");
        assertThat(check.getInputJson()).as(dumpRun()).isNotNull();
        assertThat(check.getInputJson()).contains("\"UPSTREAM\":\"PASS-42\"");
        assertThat(check.getInputJson()).contains("\"rawParams\"");
        assertThat(check.getInputJson()).doesNotContain("echo");
    }

    @Test
    @DisplayName("P-3：notify 渠道未启用时 output_json 如实记 skipped，不谎报 delivered")
    void notifyChannelUnavailable_isRecordedAsSkipped() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(),
                    Map.of("maxPcent", 93.2));
            return NodeOutcome.SUCCEEDED;
        };
        when(channelMessageRouter.pushMessage(anyString(), anyString()))
                .thenReturn(PushOutcome.CHANNEL_NOT_RUNNING);

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "notify")).isEqualTo("succeeded");
        WorkflowNodeRun notify = nodeRun(run.getId(), "notify");
        assertThat(notify.getOutputJson()).as(dumpRun()).isNotNull();
        assertThat(notify.getOutputJson()).contains("\"delivered\":[]");
        assertThat(notify.getOutputJson()).contains("\"channel\":\"telegram\"");
        assertThat(notify.getOutputJson()).contains("\"reason\":\"channel_not_running\"");
    }

    @Test
    @DisplayName("幂等重投：同一 run 并发 drive 两次，节点只执行一次（per-run 锁）")
    void concurrentDriveIsIdempotent() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            executions.incrementAndGet();
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(),
                    Map.of("maxPcent", 93.2));
            return NodeOutcome.SUCCEEDED;
        };

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // 确认第一个 drive 持锁执行节点后再重投，避免测试退化为串行调用。
            engine.drive(run);
            engine.drive(run);
        } finally {
            release.countDown();
        }

        WorkflowRun terminal = awaitTerminal(run.getId());
        assertThat(terminal.getStatus()).isEqualTo("succeeded");
        assertThat(executions.get()).isEqualTo(1);
        assertThat(nodeRunRepository.findByRunId(run.getId())).hasSize(3);
    }

    @Test
    @DisplayName("幂等：run 已终态后再次 drive 不产生任何新 attempt")
    void driveAfterTerminalIsNoop() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(),
                    Map.of("maxPcent", 93.2));
            return NodeOutcome.SUCCEEDED;
        };
        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        assertThat(awaitTerminal(run.getId()).getStatus()).as(dumpRun()).isEqualTo("succeeded");

        int before = nodeRunRepository.findByRunId(run.getId()).size();
        engine.drive(run);
        assertThat(nodeRunRepository.findByRunId(run.getId())).hasSize(before);
        assertThat(events).anySatisfy(e -> assertThat(e.type()).isEqualTo("RUN_SUCCEEDED"));
    }

    @Test
    void checkpointCannotOverwriteTerminalRun() {
        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        awaitTerminal(run.getId());
        WorkflowRun before = runRepository.findById(run.getId()).orElseThrow();
        Integer updated = new TransactionTemplate(transactionManager).execute(tx ->
                runRepository.checkpointAtomically(run.getId(), LocalDateTime.now(),
                        "[\"late\"]", "{\"late\":true}", true));
        WorkflowRun after = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated).isZero();
        assertThat(after.getVariablesJson()).isEqualTo(before.getVariablesJson());
        assertThat(after.getCurrentNodes()).isEqualTo(before.getCurrentNodes());
        assertThat(after.getHeartbeatAt()).isEqualTo(before.getHeartbeatAt());
        assertThat(after.getVariablesTruncated()).isEqualTo(before.getVariablesTruncated());
    }

    @Test
    void terminalCleanupAndEventsWaitForCommit() {
        AtomicInteger callbacks = new AtomicInteger();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            ReflectionTestUtils.invokeMethod(engine, "afterCommit", (Runnable) callbacks::incrementAndGet);
            status.setRollbackOnly();
            return null;
        });
        assertThat(callbacks.get()).isZero();
        tx.execute(status -> {
            ReflectionTestUtils.invokeMethod(engine, "afterCommit", (Runnable) callbacks::incrementAndGet);
            return null;
        });
        assertThat(callbacks.get()).isEqualTo(1);
    }

    @Test
    void runTerminalReasonsAreMaskedInDatabaseAndEvents() {
        Long version = publishDefinition(linearDefinition("skip"));
        WorkflowRun cancelled = newRunningRun(version, "{}");
        engine.cancelRun(cancelled, "failed", "token=nodesecret", "password=runsecret");
        assertThat(runRepository.findById(cancelled.getId()).orElseThrow().getErrorMessage())
                .isEqualTo("password=***");
        assertThat(nodeRunRepository.findByRunId(cancelled.getId()))
                .isNotEmpty().allSatisfy(row -> assertThat(row.getErrorMessage()).isEqualTo("token=***"));
        assertThat(events).anySatisfy(event -> {
            assertThat(event.runId()).isEqualTo(cancelled.getId());
            assertThat(event.message()).isEqualTo("password=***");
        });

        WorkflowRun failed = newRunningRun(version, "{}");
        ReflectionTestUtils.invokeMethod(engine, "failRun", failed, "token=failsecret");
        assertThat(runRepository.findById(failed.getId()).orElseThrow().getErrorMessage())
                .isEqualTo("token=***");
        assertThat(events).anySatisfy(event -> {
            assertThat(event.runId()).isEqualTo(failed.getId());
            assertThat(event.message()).isEqualTo("token=***");
        });
    }

    @Test
    void cancelTerminalRunDoesNotSetFlagOrWriteAudit() throws Exception {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> NodeOutcome.SUCCEEDED;
        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        awaitTerminal(run.getId());
        events.clear();
        engine.requestCancel(run.getId(), 42L);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getCancelRequested()).isFalse();
        verify(auditLogService, never()).logSync(eq(42L), any(), eq("WORKFLOW_RUN_CANCEL"),
                any(), eq(run.getId()), any(), nullable(String.class), any());
        assertThat(events).noneMatch(e -> "RUN_CANCELLED".equals(e.type()));
    }

    @Test
    @DisplayName("节点失败事件复用持久化的脱敏错误")
    void finishNodeMasksPersistedAndEventError() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            started.countDown();
            awaitQuietly(release);
            return NodeOutcome.SUCCEEDED;
        };
        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            WorkflowNodeRun row = attemptAllocator.startAttempt(run.getId(), "sensitive-test", "script-exec", 0);
            assertThat(row).isNotNull();
            ReflectionTestUtils.invokeMethod(engine, "finishNode", new VariableStore(), run, row,
                    "failed", "TEST_ERROR", "password=secret-test-value", null);

            String persisted = nodeRunRepository.findById(row.getId()).orElseThrow().getErrorMessage();
            assertThat(persisted).startsWith("TEST_ERROR:").doesNotContain("secret-test-value");
            assertThat(events.stream().filter(e -> "NODE_FAILED".equals(e.type())
                    && run.getId().equals(e.runId()) && "sensitive-test".equals(e.nodeKey())))
                    .singleElement().satisfies(e -> assertThat(e.message()).isEqualTo(persisted)
                            .doesNotContain("secret-test-value"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void standaloneTerminalEventRequiresNewCommittedRow() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            started.countDown();
            awaitQuietly(release);
            return NodeOutcome.SUCCEEDED;
        };
        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            WorkflowNode existing = node("check", "script-exec", Map.of());
            WorkflowNode missing = node("unreached", "script-exec", Map.of());
            events.clear();
            ReflectionTestUtils.invokeMethod(engine, "insertStandaloneTerminalRow", run.getId(), existing,
                    "skipped", "gate", "NODE_SKIPPED");
            assertThat(events).noneMatch(e -> "NODE_SKIPPED".equals(e.type()));
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> {
                ReflectionTestUtils.invokeMethod(engine, "insertStandaloneTerminalRow", run.getId(), missing,
                        "cancelled", "gate", "NODE_CANCELLED");
                assertThat(events).noneMatch(e -> "NODE_CANCELLED".equals(e.type()));
                status.setRollbackOnly();
            });
            assertThat(statusOf(run.getId(), "unreached")).isEmpty();
            assertThat(events).noneMatch(e -> "NODE_CANCELLED".equals(e.type()));
            ReflectionTestUtils.invokeMethod(engine, "insertStandaloneTerminalRow", run.getId(), missing,
                    "cancelled", "gate", "NODE_CANCELLED");
            assertThat(events.stream().filter(e -> "NODE_CANCELLED".equals(e.type())
                    && "unreached".equals(e.nodeKey()))).hasSize(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void finalizeAndCancelSerializeRunBeforeTerminalNodeInsert() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseNode = new CountDownLatch(1);
        CountDownLatch insertReached = new CountDownLatch(1);
        CountDownLatch releaseInsert = new CountDownLatch(1);
        CountDownLatch cancelStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        WorkflowRunRepository gatedRuns = (WorkflowRunRepository) Proxy.newProxyInstance(
                WorkflowRunRepository.class.getClassLoader(), new Class<?>[]{WorkflowRunRepository.class},
                (proxy, method, args) -> {
                    if ("requestCancelAtomically".equals(method.getName())) {
                        cancelStarted.countDown();
                    }
                    try {
                        return method.invoke(runRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        WorkflowNodeRunRepository gatedRepo = (WorkflowNodeRunRepository) Proxy.newProxyInstance(
                WorkflowNodeRunRepository.class.getClassLoader(), new Class<?>[]{WorkflowNodeRunRepository.class},
                (proxy, method, args) -> {
                    if ("saveAndFlush".equals(method.getName()) && args[0] instanceof WorkflowNodeRun row
                            && isTerminalRow(row, "gate")) {
                        insertReached.countDown();
                        awaitQuietly(releaseInsert);
                    }
                    try {
                        return method.invoke(nodeRunRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        WorkflowEngine gatedEngine = new WorkflowEngine(gatedRuns, gatedRepo, approvalRepository,
                versionRepository, workflowRepository, jobRepository, registry(), dagValidator, expressionEvaluator,
                attemptAllocator, auditLogService, new SensitiveDataFilter(), Optional.of(events::add), enginePool,
                retryScheduler, properties, objectMapper, transactionManager);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            started.countDown();
            awaitQuietly(releaseNode);
            return NodeOutcome.SUCCEEDED;
        };
        WorkflowRunService gatedService = new WorkflowRunService(workflowRepository, versionRepository,
                gatedRuns, gatedRepo, approvalRepository, gatedEngine, auditLogService, properties, objectMapper,
                transactionManager);
        ReflectionTestUtils.setField(gatedService, "provenanceService", provenanceService);
        publishDefinition(linearDefinition("skip"));
        WorkflowRun run = gatedService.startRun(workflowId, versionId, Map.of("threshold", 90), 42L, null, "manual");
        Thread finalizer = new Thread(() -> {
            try {
                ReflectionTestUtils.invokeMethod(gatedEngine, "finalizeRun", run);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        Thread canceller = new Thread(() -> {
            try {
                gatedEngine.requestCancel(run.getId(), 42L);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            finalizer.start();
            assertThat(insertReached.await(5, TimeUnit.SECONDS)).isTrue();
            canceller.start();
            assertThat(cancelStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseInsert.countDown();
            finalizer.join(10_000);
            canceller.join(10_000);
            assertThat(finalizer.isAlive()).isFalse();
            assertThat(canceller.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            WorkflowRun result = runRepository.findById(run.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo("succeeded");
            assertThat(result.getCancelRequested()).isFalse();
            assertThat(statusOf(run.getId(), "gate")).isEqualTo("skipped");
            verify(auditLogService, never()).logSync(eq(42L), any(), eq("WORKFLOW_RUN_CANCEL"),
                    any(), eq(run.getId()), any(), nullable(String.class), any());
        } finally {
            releaseInsert.countDown();
            releaseNode.countDown();
        }
    }

    @Test
    void terminalConflictRecognitionRequiresNamedUniqueKeyAndDuplicateCode() {
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(WorkflowEngine.class, "isNodeRunUniqueConflict",
                new SQLException("Duplicate entry for key 'workflow_node_run.uk_node_run'", "23000", 1062)))
                .isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(WorkflowEngine.class, "isNodeRunUniqueConflict",
                new SQLException("Unique index UK_NODE_RUN_INDEX_1", "23505", 23505))).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(WorkflowEngine.class, "isNodeRunUniqueConflict",
                new SQLException("Duplicate entry for key 'PRIMARY'", "23000", 1062))).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(WorkflowEngine.class, "isNodeRunUniqueConflict",
                new SQLException("uk_node_run", "23000", 1048))).isFalse();
    }

    @Test
    void cancellationAuditFailureRollsBackRunAndNodeChanges() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            started.countDown();
            awaitQuietly(release);
            return NodeOutcome.SUCCEEDED;
        };
        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            doThrow(new IllegalStateException("audit unavailable")).when(auditLogService)
                    .logSync(eq(42L), any(), eq("WORKFLOW_RUN_CANCEL"), any(), eq(run.getId()),
                            any(), nullable(String.class), any());
            assertThatThrownBy(() -> engine.requestCancel(run.getId(), 42L))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("audit unavailable");
            WorkflowRun current = runRepository.findById(run.getId()).orElseThrow();
            assertThat(current.getStatus()).isEqualTo("running");
            assertThat(current.getCancelRequested()).isFalse();
            assertThat(statusOf(run.getId(), "check")).isEqualTo("running");
            assertThat(statusOf(run.getId(), "gate")).isEmpty();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("取消与真实 attempt 事务竞争：run 锁串行化并保留唯一 attempt")
    void cancelRacingEngineAttemptInsert_stillCancels() throws Exception {
        Long version = publishNodes(List.of(node("b", "script-exec", Map.of("script", "echo b"))), List.of());
        WorkflowRun run = newRunningRun(version, "{}");
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch releaseInsert = new CountDownLatch(1);
        CountDownLatch cancelAtUpdate = new CountDownLatch(1);
        AtomicReference<WorkflowNodeRun> attempt = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        WorkflowNodeRunRepository nodeRepo = (WorkflowNodeRunRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WorkflowNodeRunRepository.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(nodeRunRepository, args);
                        if (method.getName().equals("saveAndFlush") && args[0] instanceof WorkflowNodeRun row
                                && "b".equals(row.getNodeKey()) && "running".equals(row.getStatus())) {
                            inserted.countDown();
                            if (!releaseInsert.await(15, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("等待释放 attempt 事务超时");
                            }
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        WorkflowRunRepository runRepo = (WorkflowRunRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WorkflowRunRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("requestCancelAtomically")) cancelAtUpdate.countDown();
                    try {
                        return method.invoke(runRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        AttemptAllocator allocator = new AttemptAllocator(nodeRepo, runRepo, transactionManager);
        WorkflowEngine raceEngine = new WorkflowEngine(runRepo, nodeRepo, approvalRepository, versionRepository,
                workflowRepository, jobRepository, registry(), dagValidator, expressionEvaluator, allocator,
                auditLogService, new SensitiveDataFilter(), Optional.of(events::add), enginePool,
                retryScheduler, properties, objectMapper, transactionManager);
        Thread starter = new Thread(() -> {
            try {
                attempt.set(allocator.startAttempt(run.getId(), "b", "script-exec"));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "attempt-racer");
        Thread canceller = new Thread(() -> {
            try {
                raceEngine.requestCancel(run.getId(), 42L);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "cancel-racer");
        try {
            starter.start();
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            canceller.start();
            assertThat(cancelAtUpdate.await(5, TimeUnit.SECONDS)).isTrue();
            releaseInsert.countDown();
            starter.join(15_000);
            canceller.join(15_000);
            assertThat(starter.isAlive()).isFalse();
            assertThat(canceller.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(attempt.get()).isNotNull();
            WorkflowRun terminal = runRepository.findById(run.getId()).orElseThrow();
            assertThat(terminal.getStatus()).isEqualTo("cancelled");
            assertThat(terminal.getErrorMessage()).contains("user_cancelled");
            assertThat(allocator.finishAttempt(attempt.get(),
                    AttemptAllocator.AttemptResult.builder().status("succeeded").build())).isZero();
            List<WorkflowNodeRun> rows = nodeRunRepository.findByRunId(run.getId()).stream()
                    .filter(row -> "b".equals(row.getNodeKey())).toList();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getId()).isEqualTo(attempt.get().getId());
            assertThat(rows.get(0).getAttempt()).isEqualTo(1);
            assertThat(rows.get(0).getStatus()).isEqualTo("cancelled");
        } finally {
            releaseInsert.countDown();
            starter.join(15_000);
            canceller.join(15_000);
        }
    }

    @Test
    void terminalInsertConflictRollsBackWholeCancellationAndRetries() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger insertAttempts = new AtomicInteger();
        WorkflowNodeRunRepository conflictingRepo = (WorkflowNodeRunRepository) Proxy.newProxyInstance(
                WorkflowNodeRunRepository.class.getClassLoader(), new Class<?>[]{WorkflowNodeRunRepository.class},
                (proxy, method, args) -> {
                    if ("saveAndFlush".equals(method.getName()) && args[0] instanceof WorkflowNodeRun row
                            && isTerminalRow(row, "gate") && insertAttempts.incrementAndGet() == 1) {
                        throw new org.springframework.dao.DataIntegrityViolationException("duplicate",
                                new SQLException("Duplicate entry for key 'uk_node_run'", "23000", 1062));
                    }
                    try {
                        return method.invoke(nodeRunRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        WorkflowEngine retryEngine = new WorkflowEngine(runRepository, conflictingRepo, approvalRepository,
                versionRepository, workflowRepository, jobRepository, registry(), dagValidator, expressionEvaluator,
                attemptAllocator, auditLogService, new SensitiveDataFilter(), Optional.of(events::add), enginePool,
                retryScheduler, properties, objectMapper, transactionManager);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            started.countDown();
            awaitQuietly(release);
            return NodeOutcome.SUCCEEDED;
        };
        WorkflowRunService retryService = new WorkflowRunService(workflowRepository, versionRepository,
                runRepository, conflictingRepo, approvalRepository, retryEngine, auditLogService, properties,
                objectMapper, transactionManager);
        ReflectionTestUtils.setField(retryService, "provenanceService", provenanceService);
        Long versionId = publishNodes(List.of(node("check", "script-exec", Map.of("script", "echo ok")),
                node("gate", "script-exec", Map.of("script", "echo later"))),
                List.of(edge("check", "gate")));
        WorkflowRun run = retryService.startRun(workflowId, versionId, Map.of(), 42L, null, "manual");
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            retryEngine.requestCancel(run.getId(), 42L);
            assertThat(insertAttempts.get()).isEqualTo(2);
            assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("cancelled");
            assertThat(statusOf(run.getId(), "gate")).isEqualTo("cancelled");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("节点超时后迟到输出不进入 attempt 或运行变量")
    void timedOutAttemptCannotPublishLateOutput() throws Exception {
        properties.getEngine().setDefaultNodeTimeoutSec(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch wroteLate = new CountDownLatch(1);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            started.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待测试释放超时");
                }
                ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(),
                        Map.of("maxPcent", 999));
                wroteLate.countDown();
                return NodeOutcome.SUCCEEDED;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            WorkflowRun terminal = awaitTerminal(run.getId());
            assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("failed");
            assertThat(nodeRun(run.getId(), "check").getErrorMessage()).contains("NODE_TIMEOUT");
            release.countDown();
            assertThat(wroteLate.await(5, TimeUnit.SECONDS)).isTrue();
            WorkflowNodeRun check = nodeRun(run.getId(), "check");
            assertThat(check.getStatus()).isEqualTo("failed");
            assertThat(check.getOutputJson()).isNull();
            WorkflowRun reloaded = runRepository.findById(run.getId()).orElseThrow();
            assertThat(reloaded.getVariablesJson()).doesNotContain("nodes.check.output", "maxPcent");
            assertThat(reloaded.getOutputJson()).doesNotContain("maxPcent");
            assertThat(statusOf(run.getId(), "gate")).isEqualTo("skipped");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("attempt 抢占冲突：引擎捕获 AttemptConflictException 后终止 run 并记审计 ERROR")
    void attemptConflictTerminatesRun() {
        // uk_node_run 本身挡住重复 INSERT 的行为由 WorkflowRepositoryTest 覆盖；
        // 这里用必然冲突的分配器确定性地验证引擎的兜底路径（§3.6）
        WorkflowEngine conflictingEngine = new WorkflowEngine(runRepository, nodeRunRepository,
                approvalRepository, versionRepository, workflowRepository, jobRepository, registry(), dagValidator,
                expressionEvaluator, new ConflictingAllocator(nodeRunRepository, runRepository, transactionManager),
                auditLogService, new SensitiveDataFilter(), Optional.of(events::add), enginePool,
                retryScheduler, properties, objectMapper, transactionManager);
        WorkflowRunService conflictingRunService = new WorkflowRunService(workflowRepository,
                versionRepository, runRepository, nodeRunRepository, approvalRepository,
                conflictingEngine, auditLogService, properties, objectMapper, transactionManager);
        ReflectionTestUtils.setField(conflictingRunService, "provenanceService", provenanceService);

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
    @DisplayName("失败策略 partial：上游失败后仍尝试条件求值，求值失败不选择分支")
    void failurePolicyPartial() {
        stub(NodeType.SCRIPT_EXEC).behavior = failCheckThenSucceed();

        WorkflowRun run = startRun(linearDefinition("partial"), Map.of("threshold", 90));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "check")).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "gate")).as(dumpRun()).isEqualTo("failed");
        assertThat(nodeRun(run.getId(), "gate").getErrorMessage()).contains("CONDITION_EVALUATION_FAILED");
        assertThat(nodeRun(run.getId(), "gate").getOutputJson()).isNull();
        assertThat(statusOf(run.getId(), "notify")).isEqualTo("skipped");
    }

    @Test
    void strictAiFailureAbortsPartialMultiHopWithoutSideEffects() {
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("{\"answer\":42}"));
        WorkflowRun run = startRun(strictPartialDefinition(), Map.of());
        WorkflowRun terminal = awaitTerminal(run.getId());
        assertThat(terminal.getStatus()).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "ai")).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "middle")).isEqualTo("cancelled");
        assertThat(statusOf(run.getId(), "effect")).isEqualTo("cancelled");
        verify(channelMessageRouter, never()).pushMessage(anyString(), anyString());
        assertThat(nodeRunRepository.findByRunId(run.getId()).stream()
                .filter(row -> "ai".equals(row.getNodeKey())).count()).isEqualTo(1);
    }

    @Test
    void strictAiFailureOnRecoveryPreventsRedriveAndResume() {
        Long version = publishDefinition(strictPartialDefinition());
        for (boolean resume : List.of(false, true)) {
            WorkflowRun run = newRunningRun(version, "{}");
            WorkflowNodeRun failed = new WorkflowNodeRun();
            failed.setId("wnr_" + java.util.UUID.randomUUID().toString().replace("-", ""));
            failed.setRunId(run.getId());
            failed.setNodeKey("ai");
            failed.setNodeType("llm");
            failed.setAttempt(1);
            failed.setStatus("failed");
            nodeRunRepository.saveAndFlush(failed);
            if (resume) {
                WorkflowNodeRun running = new WorkflowNodeRun();
                running.setId("wnr_" + java.util.UUID.randomUUID().toString().replace("-", ""));
                running.setRunId(run.getId());
                running.setNodeKey("effect");
                running.setNodeType("notify");
                running.setAttempt(1);
                running.setStatus("running");
                nodeRunRepository.saveAndFlush(running);
                engine.resumeNodes(run.getId(), List.of("effect"));
            } else {
                engine.drive(run);
            }
            assertThat(awaitTerminal(run.getId()).getStatus()).isEqualTo("failed");
            // An unclaimed running attempt is not falsely reported as physically stopped.
            assertThat(statusOf(run.getId(), "effect")).isEqualTo(resume ? "running" : "cancelled");
        }
        verify(channelMessageRouter, never()).pushMessage(anyString(), anyString());
    }

    private String strictPartialDefinition() {
        return """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"partial",
                 "nodes":[
                   {"key":"ai","type":"llm","onFailure":"partial","retryMax":3,
                    "config":{"userPrompt":"answer","outputMode":"json_schema_v1",
                              "jsonSchema":"{\\"type\\":\\"object\\",\\"properties\\":{\\"answer\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"answer\\"],\\"additionalProperties\\":false}"}},
                   {"key":"middle","type":"transform","onFailure":"partial","config":{}},
                   {"key":"effect","type":"notify","config":{"channels":["telegram"],"messageTemplate":"side effect"}}],
                 "edges":[{"id":"e1","from":"ai","to":"middle","onUpstreamFailure":"partial"},
                          {"id":"e2","from":"middle","to":"effect","onUpstreamFailure":"partial"}]}
                """;
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
        WorkflowApproval decided = new TransactionTemplate(transactionManager).execute(
                status -> approvalStore.decide(approval.getId(), "approved", 9L, "同意"));
        assertThat(decided).isNotNull();

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

    /**
     * node_run 仓储装饰代理：把指定节点的 attempt INSERT / 终态留痕 INSERT 闩住，放行时机
     * 交给测试线程——"取消 vs 引擎并发 attempt"的竞态不能靠 sleep 赌。
     *
     * <p>为什么用 JDK 动态代理而不是 {@code @MockitoSpyBean}：后者对 Spring Data 仓储接口
     * 造出的间谍没法 {@code callRealMethod()}（Mockito 报 "Cannot call abstract real method"），
     * 代理则原样转发给真身，异常也原样透传（不包一层反射异常）。</p>
     */
    private WorkflowNodeRunRepository latchingNodeRunRepository(
            String nodeKey,
            CountDownLatch engineAtAttemptInsert, CountDownLatch releaseEngineAttempt,
            CountDownLatch cancelAtTerminalInsert, CountDownLatch releaseCancelTerminal) {
        return (WorkflowNodeRunRepository) Proxy.newProxyInstance(
                WorkflowNodeRunRepository.class.getClassLoader(),
                new Class<?>[]{WorkflowNodeRunRepository.class},
                (proxy, method, args) -> {
                    Object entity = args != null && args.length == 1 ? args[0] : null;
                    if (entity instanceof WorkflowNodeRun row
                            && ("save".equals(method.getName()) || "saveAndFlush".equals(method.getName()))) {
                        if (!Boolean.TRUE.equals(CANCEL_PATH.get()) && isRunningRow(row, nodeKey)) {
                            engineAtAttemptInsert.countDown();
                            awaitQuietly(releaseEngineAttempt);
                        } else if (Boolean.TRUE.equals(CANCEL_PATH.get()) && isTerminalRow(row, nodeKey)) {
                            cancelAtTerminalInsert.countDown();
                            awaitQuietly(releaseCancelTerminal);
                        }
                    }
                    try {
                        return method.invoke(nodeRunRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause() == null ? e : e.getCause();
                    }
                });
    }

    /** attempt 抢占行：引擎侧 startAttempt 写的 status=running 行 */
    private static boolean isRunningRow(Object candidate, String nodeKey) {
        if (!(candidate instanceof WorkflowNodeRun row)) {
            return false;
        }
        return nodeKey.equals(row.getNodeKey()) && "running".equals(row.getStatus());
    }

    /** 终态留痕行：取消/收尾路径给"从未开始"的节点写的 attempt=1 cancelled/skipped 行 */
    private static boolean isTerminalRow(Object candidate, String nodeKey) {
        if (!(candidate instanceof WorkflowNodeRun row)) {
            return false;
        }
        return nodeKey.equals(row.getNodeKey()) && Set.of("cancelled", "skipped").contains(row.getStatus());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("运行期祖先隔离：节点读不到兄弟节点输出（master store 里存在也不算）")
    void runtimeAncestorIsolationHidesSiblingOutputs() {
        AtomicReference<Boolean> sawSibling = new AtomicReference<>(null);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            switch (ctx.node().getKey()) {
                case "a" -> ctx.variables().putNodeOutput("a", null, Map.of("secret", "sibling"));
                case "c" -> {
                    long deadline = System.currentTimeMillis() + 5000;
                    while (ctx.variables().nodeOutput("a") == null
                            && System.currentTimeMillis() < deadline) {
                        sleepQuietly(10);
                    }
                }
                case "b" -> sawSibling.set(
                        ctx.resolver().resolve("nodes.a.output.secret").isPresent());
                default -> {
                }
            }
            return NodeOutcome.SUCCEEDED;
        };

        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip",
                 "nodes":[
                   {"key":"root","type":"script-exec","config":{"script":"echo root"}},
                   {"key":"gate","type":"condition","config":{"expression":"true"}},
                   {"key":"a","type":"script-exec","config":{"script":"echo a"}},
                   {"key":"c","type":"script-exec","config":{"script":"echo c"}},
                   {"key":"b","type":"script-exec","config":{"script":"echo b"}}],
                 "edges":[
                   {"id":"e1","from":"root","to":"gate"},
                   {"id":"e2","from":"gate","to":"a","sourceHandle":"true"},
                   {"id":"e3","from":"gate","to":"c","sourceHandle":"true"},
                   {"id":"e4","from":"c","to":"b"}]}
                """;
        WorkflowRun run = startRun(definition, Map.of());
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        // c 在 master store 里能看到 a（证明 a 确实先产出），但 b 的 resolver 被裁剪
        assertThat(statusOf(run.getId(), "c")).isEqualTo("succeeded");
        assertThat(sawSibling.get()).as("b 不得读到非祖先 a 的输出").isFalse();
    }

    @Test
    @DisplayName("重驱动恢复 succeeded condition 输出：只走正确分支（不静默双跳）")
    void redriveRestoresConditionOutput() {
        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip",
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"gate","type":"condition","config":{"expression":"true"}},
                   {"key":"yes","type":"notify","config":{"channels":["telegram"],"messageTemplate":"yes"}},
                   {"key":"no","type":"notify","config":{"channels":["telegram"],"messageTemplate":"no"}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"gate"},
                   {"id":"e2","from":"gate","to":"yes","sourceHandle":"true"},
                   {"id":"e3","from":"gate","to":"no","sourceHandle":"false"}]}
                """;
        Long version = publishDefinition(definition);

        WorkflowRun run = newRunningRun(version, "{}");
        saveSucceededNodeRun(run.getId(), "gate", "condition", "{\"result\":true}");

        engine.drive(run);

        WorkflowRun terminal = awaitTerminal(run.getId());
        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "yes")).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "no")).as(dumpRun()).isEqualTo("skipped");
    }

    @Test
    @DisplayName("condition 输出无法恢复（非布尔）→ run failed VARIABLE_RESTORE_FAILED")
    void redriveFailsClosedWhenConditionOutputUnrecoverable() {
        String definition = """
                {"schemaVersion":1,
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"gate","type":"condition","config":{"expression":"true"}},
                   {"key":"yes","type":"notify","config":{"channels":["telegram"],"messageTemplate":"yes"}}],
                 "edges":[{"id":"e1","from":"begin","to":"gate"},
                          {"id":"e2","from":"gate","to":"yes","sourceHandle":"true"}]}
                """;
        Long version = publishDefinition(definition);

        WorkflowRun run = newRunningRun(version, "{}");
        saveSucceededNodeRun(run.getId(), "gate", "condition", "{\"result\":\"yes\"}");

        engine.drive(run);

        WorkflowRun terminal = awaitTerminal(run.getId());
        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("failed");
        assertThat(terminal.getErrorMessage()).contains("VARIABLE_RESTORE_FAILED");
        assertThat(statusOf(run.getId(), "yes")).as(dumpRun()).isEmpty();
    }

    @Test
    @DisplayName("恢复 condition 失败后重试不得复用部分变量存储")
    void failedVariableRestoreDoesNotPublishPartialStore() throws Exception {
        String definition = """
                {"schemaVersion":1,
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"gate","type":"condition","config":{"expression":"true"}}],
                 "edges":[{"id":"e1","from":"begin","to":"gate"}]}
                """;
        WorkflowRun run = newRunningRun(publishDefinition(definition), "{\"threshold\":90}");
        saveSucceededNodeRun(run.getId(), "begin", "start", "{\"value\":1}");
        saveSucceededNodeRun(run.getId(), "gate", "condition", "{\"result\":\"yes\"}");

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> engine.initVariablesIfAbsent(run))
                    .isInstanceOf(NodeFailedException.class)
                    .hasMessageContaining("gate");
        }

        String repairedFixture = "{\"result\":true}";
        String fixtureDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(repairedFixture.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("UPDATE workflow_node_run SET output_json = ?, output_sha256 = ? WHERE run_id = ? AND node_key = ?",
                repairedFixture, fixtureDigest, run.getId(), "gate");
        engine.initVariablesIfAbsent(run);

        assertThat(engine.variableStore(run.getId()).resolve("run.input.threshold")).contains(90);
        assertThat(engine.variableStore(run.getId()).nodeOutput("begin").value())
                .isEqualTo(Map.of("value", 1));
        assertThat(engine.variableStore(run.getId()).nodeOutput("gate").value())
                .isEqualTo(Map.of("result", true));
    }

    @Test
    @DisplayName("大 End 输出（>8KB）：run.output_json 仍是合法对象，API presenter 可解析")
    void largeEndOutputSurvivesPreviewChannel() throws Exception {
        String definition = """
                {"schemaVersion":1,
                 "variables":{"blob":{"type":"string","required":true},
                              "secret":{"type":"string","required":true}},
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"finish","type":"end","config":{"outputs":[
                     {"name":"summary","value":"${run.input.blob}"},
                     {"name":"token","value":"${run.input.secret}"}]}}],
                 "edges":[{"id":"e1","from":"begin","to":"finish"}]}
                """;
        String blob = "x".repeat(9000);
        WorkflowRun run = startRun(definition, Map.of("blob", blob, "secret", "token=supersecret"));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        @SuppressWarnings("unchecked")
        Map<String, Object> persisted = objectMapper.readValue(terminal.getOutputJson(), Map.class);
        assertThat((String) persisted.get("summary")).hasSize(9000);

        com.easyshell.server.workflow.service.WorkflowApiPresenter presenter =
                new com.easyshell.server.workflow.service.WorkflowApiPresenter(
                        objectMapper, new SensitiveDataFilter(), workflowRepository, runRepository);
        @SuppressWarnings("unchecked")
        Map<String, Object> presented = (Map<String, Object>) presenter.run(terminal).getOutput();
        assertThat(presented).isNotNull();
        assertThat((String) presented.get("summary")).hasSize(9000);
        // End 从 run.input 复制的秘密在 API 出口被现有 SensitiveDataFilter 掩码
        assertThat((String) presented.get("token")).isEqualTo("***");
    }

    @Test
    @DisplayName("End 未执行（skipped）：run.output 回退为旧聚合，不丢其它节点输出")
    void skippedEndFallsBackToAggregateOutput() throws Exception {
        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip",
                 "variables":{"threshold":{"type":"number","required":true}},
                 "nodes":[
                   {"key":"begin","type":"script-exec","config":{"script":"echo begin"}},
                   {"key":"gate","type":"condition","config":{"expression":"run.input.threshold > 90"}},
                   {"key":"finish","type":"end","config":{"outputs":[
                     {"name":"summary","value":"high"}]}},
                   {"key":"notify","type":"notify","config":{"channels":["telegram"],"messageTemplate":"low"}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"gate"},
                   {"id":"e2","from":"gate","to":"finish","sourceHandle":"true"},
                   {"id":"e3","from":"gate","to":"notify","sourceHandle":"false"}]}
                """;
        WorkflowRun run = startRun(definition, Map.of("threshold", 5));
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "finish")).as(dumpRun()).isEqualTo("skipped");
        assertThat(statusOf(run.getId(), "notify")).isEqualTo("succeeded");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = objectMapper.readValue(terminal.getOutputJson(), Map.class);
        assertThat(output).containsKey("notify");
        assertThat(output).doesNotContainKey("summary");
    }

    @Test
    @DisplayName("End 执行失败：run.output 回退为旧聚合，不把失败节点的残缺输出当整体输出")
    void failedEndFallsBackToAggregateOutput() throws Exception {
        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip",
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"gate","type":"condition","config":{"expression":"true"}},
                   {"key":"finish","type":"end","config":{"outputs":[
                     {"name":"summary","value":"${nodes.ghost.output.text}"}]}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"gate"},
                   {"id":"e2","from":"gate","to":"finish","sourceHandle":"true"}]}
                """;
        WorkflowRun run = startRun(definition, Map.of());
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("failed");
        assertThat(statusOf(run.getId(), "finish")).as(dumpRun()).isEqualTo("failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = objectMapper.readValue(terminal.getOutputJson(), Map.class);
        assertThat(output).containsKey("gate");
        assertThat(output).doesNotContainKey("summary");
    }

    @Test
    @DisplayName("condition 输出缺失：true/false 两条边都 fail-closed（不误放行）")
    void missingConditionOutputSkipsBothBranches() {
        StubExecutor conditionStub = new StubExecutor(NodeType.CONDITION);
        conditionStub.behavior = ctx -> NodeOutcome.SUCCEEDED;   // 故意不写 output
        NodeExecutorRegistry localRegistry = new NodeExecutorRegistry(List.of(
                new StubExecutor(NodeType.SCRIPT_EXEC),
                new StartNodeExecutor(),
                conditionStub,
                new NotifyNodeExecutor(channelMessageRouter, expressionEvaluator, new SensitiveDataFilter())));
        WorkflowEngine localEngine = new WorkflowEngine(runRepository, nodeRunRepository,
                approvalRepository, versionRepository, workflowRepository, jobRepository,
                localRegistry, dagValidator, expressionEvaluator, attemptAllocator,
                auditLogService, new SensitiveDataFilter(), Optional.of(events::add), enginePool,
                retryScheduler, properties, objectMapper, transactionManager);

        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip",
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"gate","type":"condition","config":{"expression":"true"}},
                   {"key":"yes","type":"notify","config":{"channels":["telegram"],"messageTemplate":"yes"}},
                   {"key":"no","type":"notify","config":{"channels":["telegram"],"messageTemplate":"no"}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"gate"},
                   {"id":"e2","from":"gate","to":"yes","sourceHandle":"true"},
                   {"id":"e3","from":"gate","to":"no","sourceHandle":"false"}]}
                """;
        Long version = publishDefinition(definition);
        WorkflowRun run = newRunningRun(version, "{}");

        localEngine.drive(run);

        WorkflowRun terminal = awaitTerminal(run.getId());
        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "yes")).as(dumpRun()).isEqualTo("skipped");
        assertThat(statusOf(run.getId(), "no")).as(dumpRun()).isEqualTo("skipped");
    }

    @Test
    @DisplayName("非法 sourceHandle：运行期 fail-closed（发布期另由 DagValidator 拒绝）")
    void invalidSourceHandleFailsClosedAtRuntime() {
        String definition = """
                {"schemaVersion":1,"defaultOnUpstreamFailure":"skip",
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"gate","type":"condition","config":{"expression":"true"}},
                   {"key":"yes","type":"notify","config":{"channels":["telegram"],"messageTemplate":"yes"}},
                   {"key":"no","type":"notify","config":{"channels":["telegram"],"messageTemplate":"no"}}],
                 "edges":[
                   {"id":"e1","from":"begin","to":"gate"},
                   {"id":"e2","from":"gate","to":"yes","sourceHandle":"maybe"},
                   {"id":"e3","from":"gate","to":"no","sourceHandle":"false"}]}
                """;
        WorkflowRun run = startRun(definition, Map.of());
        WorkflowRun terminal = awaitTerminal(run.getId());

        assertThat(terminal.getStatus()).as(dumpRun()).isEqualTo("succeeded");
        assertThat(statusOf(run.getId(), "yes")).as(dumpRun()).isEqualTo("skipped");
        assertThat(statusOf(run.getId(), "no")).isEqualTo("skipped");
    }

    @Test
    @DisplayName("队列泵回不把 queued 算活跃：3 个排队、上限 1 时只启动 1 个，不饿死")
    void queuedPumpDoesNotCountQueuedAsActive() throws Exception {
        properties.getEngine().setMaxConcurrentRuns(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            started.countDown();
            awaitQuietly(release);
            return NodeOutcome.SUCCEEDED;
        };
        String definition = """
                {"schemaVersion":1,
                 "nodes":[
                   {"key":"begin","type":"start","config":{}},
                   {"key":"work","type":"script-exec","config":{"script":"echo work"}}],
                 "edges":[{"id":"e1","from":"begin","to":"work"}]}
                """;
        Long version = publishDefinition(definition);
        List<String> queuedIds = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            WorkflowRun queued = newRunningRun(version, "{}");
            queued.setStatus("queued");
            runRepository.saveAndFlush(queued);
            queuedIds.add(queued.getId());
        }

        WorkflowPendingWakeupScanner scanner = new WorkflowPendingWakeupScanner(
                runRepository, engine, auditLogService, properties, transactionManager);
        scanner.scanOnce();

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        // 只统计本用例创建的三条 run，避开其它异步收尾任务可能的干扰
        long startedCount = queuedIds.stream()
                .map(id -> runRepository.findById(id).map(WorkflowRun::getStatus).orElse("missing"))
                .filter(status -> status.equals("running") || status.equals("waiting"))
                .count();
        long stillQueued = queuedIds.stream()
                .map(id -> runRepository.findById(id).map(WorkflowRun::getStatus).orElse("missing"))
                .filter("queued"::equals)
                .count();
        assertThat(startedCount).isEqualTo(1);
        assertThat(stillQueued).isEqualTo(2);
        release.countDown();
    }

    @ParameterizedTest(name = "claimed approval cleanup: {0}")
    @ValueSource(strings = {"cancel", "variables", "missingDefinition", "abort"})
    void resumeClosesClaimedAttemptWhenStoppedBeforeExecution(String scenario) {
        WorkflowNode gate = node("gate", "approval", Map.of("title", "gate"));
        WorkflowNode condition = node("condition", "condition", Map.of("expression", "true"));
        WorkflowNode failedNode = node("failed", "script-exec", Map.of());
        failedNode.setOnFailure("abort");
        WorkflowNode secondGate = node("gate2", "approval", Map.of("title", "gate2"));
        Long version = publishNodes(List.of(gate, secondGate, condition, failedNode), List.of());
        WorkflowRun run = newRunningRun("missingDefinition".equals(scenario) ? Long.MAX_VALUE : version, "{}");
        run.setStatus("waiting");
        run.setCancelRequested("cancel".equals(scenario));
        runRepository.saveAndFlush(run);
        WorkflowNodeRun claimed = waitingAttempt(run.getId(), "gate");
        WorkflowNodeRun secondClaimed = waitingAttempt(run.getId(), "gate2");

        WorkflowNodeRun unrelated = new WorkflowNodeRun();
        unrelated.setId("wnr_" + java.util.UUID.randomUUID());
        unrelated.setRunId(run.getId());
        unrelated.setNodeKey("unrelated");
        unrelated.setNodeType("script-exec");
        unrelated.setStatus("running");
        nodeRunRepository.saveAndFlush(unrelated);

        if ("variables".equals(scenario) || "abort".equals(scenario)) {
            WorkflowNodeRun prior = new WorkflowNodeRun();
            prior.setId("wnr_" + java.util.UUID.randomUUID());
            prior.setRunId(run.getId());
            prior.setNodeKey("variables".equals(scenario) ? "condition" : "failed");
            prior.setNodeType("variables".equals(scenario) ? "condition" : "script-exec");
            prior.setStatus("variables".equals(scenario) ? "succeeded" : "failed");
            // A succeeded condition without a boolean result cannot be restored.
            prior.setOutputJson("{}");
            nodeRunRepository.saveAndFlush(prior);
        }

        engine.resumeRun(run.getId());

        WorkflowRun terminal = runRepository.findById(run.getId()).orElseThrow();
        assertThat(terminal.getStatus()).isEqualTo("cancel".equals(scenario) ? "cancelled" : "failed");
        if ("variables".equals(scenario)) {
            assertThat(terminal.getErrorMessage()).contains("VARIABLE_RESTORE_FAILED");
        }
        if ("missingDefinition".equals(scenario)) {
            assertThat(terminal.getErrorMessage()).contains("definition_missing");
        }
        WorkflowNodeRun closed = nodeRunRepository.findById(claimed.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo("cancelled");
        assertThat(closed.getFinishedAt()).isNotNull();
        WorkflowNodeRun secondClosed = nodeRunRepository.findById(secondClaimed.getId()).orElseThrow();
        assertThat(secondClosed.getStatus()).isEqualTo("cancelled");
        assertThat(secondClosed.getFinishedAt()).isNotNull();
        assertThat(nodeRunRepository.findById(unrelated.getId()).orElseThrow().getStatus()).isEqualTo("running");
        assertThat(nodeRunRepository.findByRunId(run.getId()).stream()
                .filter(row -> "gate".equals(row.getNodeKey()))).hasSize(1);
        assertThat(events.stream().filter(e -> "NODE_STARTED".equals(e.type())
                && run.getId().equals(e.runId()) && List.of("gate", "gate2").contains(e.nodeKey()))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void resumePoolRejectionClosesOnlyNeverAcceptedClaims(int accepted) {
        properties.getEngine().setMaxConcurrentNodes(2);
        Long version = publishNodes(List.of(
                node("first", "approval", Map.of("title", "first")),
                node("second", "approval", Map.of("title", "second"))), List.of());
        WorkflowRun run = newRunningRun(version, "{}");
        run.setStatus("waiting");
        runRepository.saveAndFlush(run);
        waitingAttempt(run.getId(), "first");
        waitingAttempt(run.getId(), "second");

        java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch rejected = new CountDownLatch(1);
        java.util.concurrent.ThreadPoolExecutor controlled = new java.util.concurrent.ThreadPoolExecutor(
                4, 4, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>()) {
            @Override public void execute(Runnable command) {
                int number = submissions.incrementAndGet();
                if (number == accepted + 1) {
                    rejected.countDown();
                    throw new java.util.concurrent.RejectedExecutionException("controlled rejection");
                }
                if (accepted == 1 && number == 1) {
                    super.execute(() -> {
                        try {
                            if (!rejected.await(5, TimeUnit.SECONDS)) throw new AssertionError("rejection timeout");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        command.run();
                    });
                } else {
                    super.execute(command);
                }
            }
        };
        WorkflowEngine testEngine = new WorkflowEngine(runRepository, nodeRunRepository, approvalRepository,
                versionRepository, workflowRepository, jobRepository, registry(), dagValidator, expressionEvaluator,
                attemptAllocator, auditLogService, new SensitiveDataFilter(), Optional.of(events::add), controlled,
                retryScheduler, properties, objectMapper, transactionManager);
        try {
            testEngine.resumeRun(run.getId());
            assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("failed");
            List<WorkflowNodeRun> rows = nodeRunRepository.findByRunId(run.getId());
            assertThat(rows).hasSize(2);
            assertThat(rows.stream().filter(row -> "succeeded".equals(row.getStatus())).count()).isEqualTo(accepted);
            assertThat(rows.stream().filter(row -> "cancelled".equals(row.getStatus()))
                    .filter(row -> row.getErrorMessage().contains("resume_aborted")).count()).isEqualTo(2 - accepted);
            assertThat(events.stream().filter(e -> run.getId().equals(e.runId())
                    && "NODE_STARTED".equals(e.type())).count()).isEqualTo(accepted);
            assertThat(events.stream().filter(e -> run.getId().equals(e.runId())
                    && "RUN_FAILED".equals(e.type())).count()).isEqualTo(1);
            testEngine.resumeRun(run.getId());
            assertThat(submissions.get()).isEqualTo(accepted == 0 ? 1 : 3);
        } finally {
            controlled.shutdownNow();
        }
    }

    @Test
    void resumesAllClaimedApprovalsInWavesOfOne() {
        properties.getEngine().setMaxConcurrentNodes(1);
        Long version = publishNodes(List.of(
                node("first", "approval", Map.of("title", "first")),
                node("second", "approval", Map.of("title", "second"))), List.of());
        WorkflowRun run = newRunningRun(version, "{}");
        run.setStatus("waiting");
        runRepository.saveAndFlush(run);
        WorkflowNodeRun first = waitingAttempt(run.getId(), "first");
        WorkflowNodeRun second = waitingAttempt(run.getId(), "second");

        engine.resumeRun(run.getId());

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("succeeded");
        assertThat(nodeRunRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo("succeeded");
        assertThat(nodeRunRepository.findById(second.getId()).orElseThrow().getStatus()).isEqualTo("succeeded");
        assertThat(nodeRunRepository.findByRunId(run.getId())).hasSize(2);
        assertThat(events.stream().filter(e -> "NODE_STARTED".equals(e.type()))).hasSize(2);
    }

    @Test
    void rejectedApprovalRunsAloneAndAbortCancelsApprovedAttempt() {
        assertApprovalDecisionWaves("abort", "rejected", "reject", "cancelled", "failed");
    }

    @Test
    void rejectedApprovalCanContinueWhenPolicyAllows() {
        assertApprovalDecisionWaves("continue", "rejected", "reject", "succeeded", "failed");
    }

    @Test
    void timedOutApprovalCanContinueWhenFailurePolicyAllows() {
        assertApprovalDecisionWaves("continue", "timeout", "reject", "succeeded", "failed");
    }

    @Test
    void timeoutWithNodeAbortStopsRemainingApproval() {
        properties.getEngine().setMaxConcurrentNodes(5);
        WorkflowNode negativeNode = node("negative", "approval", Map.of("title", "gate", "onTimeout", "fail"));
        negativeNode.setOnFailure("abort");
        Long version = publishNodes(List.of(negativeNode, node("approved", "approval", Map.of("title", "ok"))),
                List.of());
        WorkflowRun run = newRunningRun(version, "{}");
        run.setStatus("waiting");
        runRepository.saveAndFlush(run);
        WorkflowNodeRun negative = waitingAttempt(run.getId(), "negative");
        WorkflowNodeRun approved = waitingAttempt(run.getId(), "approved");
        WorkflowApproval decision = approvalRepository.findById(negative.getApprovalId()).orElseThrow();
        decision.setStatus("timeout");
        approvalRepository.saveAndFlush(decision);

        engine.resumeRun(run.getId());

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("failed");
        assertThat(nodeRunRepository.findById(negative.getId()).orElseThrow().getErrorMessage())
                .contains("approval_timeout");
        assertThat(nodeRunRepository.findById(approved.getId()).orElseThrow().getStatus()).isEqualTo("cancelled");
        assertThat(events.stream().filter(e -> "NODE_STARTED".equals(e.type()))).hasSize(1);
    }

    @Test
    void timeoutApproveCompletesBothAttempts() {
        assertApprovalDecisionWaves("abort", "timeout", "approve", "succeeded", "succeeded");
    }

    private void assertApprovalDecisionWaves(String onRejection, String decision, String onTimeout,
                                             String otherStatus, String expectedRunStatus) {
        properties.getEngine().setMaxConcurrentNodes(5);
        WorkflowDefinition def = new WorkflowDefinition();
        def.setSchemaVersion(1);
        def.setDefaultOnUpstreamFailure("skip");
        def.setOnRejection(onRejection);
        def.setNodes(List.of(node("negative", "approval", Map.of("title", "negative", "onTimeout", onTimeout)),
                node("approved", "approval", Map.of("title", "approved"))));
        def.setEdges(List.of());
        Long version;
        try {
            version = publishDefinition(objectMapper.writeValueAsString(def));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        WorkflowRun run = newRunningRun(version, "{}");
        run.setStatus("waiting");
        runRepository.saveAndFlush(run);
        WorkflowNodeRun negative = waitingAttempt(run.getId(), "negative");
        WorkflowNodeRun approved = waitingAttempt(run.getId(), "approved");
        WorkflowApproval decisionRow = approvalRepository.findById(negative.getApprovalId()).orElseThrow();
        decisionRow.setStatus(decision);
        approvalRepository.saveAndFlush(decisionRow);

        engine.resumeRun(run.getId());

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(expectedRunStatus);
        assertThat(nodeRunRepository.findById(approved.getId()).orElseThrow().getStatus()).isEqualTo(otherStatus);
        WorkflowNodeRun result = nodeRunRepository.findById(negative.getId()).orElseThrow();
        if ("timeout".equals(decision) && "approve".equals(onTimeout)) {
            assertThat(result.getStatus()).isEqualTo("succeeded");
            assertThat(result.getOutputJson()).contains("\"decision\":\"timeout\"");
        } else {
            assertThat(result.getStatus()).isEqualTo("failed");
            assertThat(result.getErrorMessage()).contains("approval_" + decision);
        }
        List<WorkflowEvent> starts = events.stream().filter(e -> "NODE_STARTED".equals(e.type())).toList();
        assertThat(starts).isNotEmpty();
        assertThat(starts.get(0).nodeKey()).isEqualTo("negative");
        assertThat(starts).hasSize("cancelled".equals(otherStatus) ? 1 : 2);
    }

    @Test
    void nonApprovalWaitingIsNotRetriedEvenWithRetryConfigured() {
        stub(NodeType.WAIT).behavior = ctx -> NodeOutcome.WAITING;
        WorkflowRun run = startRun("""
                {"schemaVersion":1,"nodes":[{"key":"wait","type":"wait","onFailure":"abort",
                 "retryMax":2,"retryIntervalSec":1,"config":{"durationSec":1}}],"edges":[]}
                """, Map.of());

        assertThat(awaitTerminal(run.getId()).getStatus()).isEqualTo("failed");
        sleepQuietly(1500);
        var rows = nodeRunRepository.findByRunId(run.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo("failed");
        assertThat(rows.get(0).getErrorMessage()).startsWith("WAITING_UNSUPPORTED:");
        assertThat(stub(NodeType.WAIT).calls).hasValue(1);
    }

    @ParameterizedTest(name = "non-approval WAITING, foreign approval={0}")
    @ValueSource(booleans = {false, true})
    void nonApprovalWaitingMustNotCreateOrOverwriteApprovalBinding(boolean foreignApproval) {
        Long version = publishNodes(List.of(node("script", "script-exec", Map.of())), List.of());
        WorkflowRun run = newRunningRun(version, "{}");
        WorkflowRun other = newRunningRun(version, "{}");
        WorkflowApproval original = foreignApproval ? approvalFixture(run.getId(), "script", "approval") : null;
        WorkflowApproval foreign = foreignApproval ? approvalFixture(other.getId(), "script", "approval") : null;
        long approvalCount = approvalRepository.count();
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            if (original != null) {
                WorkflowNodeRun persisted = nodeRunRepository.findById(ctx.nodeRun().getId()).orElseThrow();
                persisted.setApprovalId(original.getId());
                nodeRunRepository.saveAndFlush(persisted);
                ctx.nodeRun().setApprovalId(foreign.getId());
            } else {
                // An unpersisted executor-supplied binding must not be installed either.
                ctx.nodeRun().setApprovalId(123456789L);
            }
            return NodeOutcome.WAITING;
        };

        engine.drive(run);

        WorkflowNodeRun actual = nodeRun(run.getId(), "script");
        assertThat(actual.getApprovalId()).as("existing binding must not be overwritten")
                .isEqualTo(original == null ? null : original.getId());
        assertThat(actual.getStatus()).isNotEqualTo("waiting");
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isNotEqualTo("waiting");
        assertThat(approvalRepository.count()).isEqualTo(approvalCount);
        if (foreign != null) {
            assertThat(approvalRepository.findById(foreign.getId()).orElseThrow().getRunId()).isEqualTo(other.getId());
        }
    }

    @ParameterizedTest(name = "cancel before non-approval WAITING, foreign approval={0}")
    @ValueSource(booleans = {false, true})
    void cancellationWinsAgainstLateNonApprovalWaiting(boolean foreignApproval) throws Exception {
        terminalWinsAgainstLateNonApprovalWaiting(foreignApproval, "cancelled");
    }

    @ParameterizedTest(name = "timeout before non-approval WAITING, foreign approval={0}")
    @ValueSource(booleans = {false, true})
    void timeoutWinsAgainstLateNonApprovalWaiting(boolean foreignApproval) throws Exception {
        terminalWinsAgainstLateNonApprovalWaiting(foreignApproval, "timeout");
    }

    private void terminalWinsAgainstLateNonApprovalWaiting(boolean foreignApproval, String terminal) throws Exception {
        Long version = publishNodes(List.of(node("script", "script-exec", Map.of())), List.of());
        WorkflowRun run = newRunningRun(version, "{}");
        WorkflowRun other = newRunningRun(version, "{}");
        WorkflowApproval original = foreignApproval ? approvalFixture(run.getId(), "script", "approval") : null;
        WorkflowApproval foreign = foreignApproval ? approvalFixture(other.getId(), "script", "approval") : null;
        long approvalCount = approvalRepository.count();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> driveFailure = new AtomicReference<>();
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            if (original != null) {
                WorkflowNodeRun persisted = nodeRunRepository.findById(ctx.nodeRun().getId()).orElseThrow();
                persisted.setApprovalId(original.getId());
                nodeRunRepository.saveAndFlush(persisted);
                ctx.nodeRun().setApprovalId(foreign.getId());
            }
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("executor release timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return NodeOutcome.WAITING;
        };
        Thread driver = new Thread(() -> {
            try { engine.drive(run); } catch (Throwable failure) { driveFailure.set(failure); }
        }, "late-waiting-driver");
        driver.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            engine.cancelRun(run, terminal, "cancelled".equals(terminal) ? "user_cancelled" : "run_timeout");
        } finally {
            release.countDown();
            driver.join(15_000);
        }
        assertThat(driver.isAlive()).isFalse();
        assertThat(driveFailure.get()).isNull();
        WorkflowNodeRun actual = nodeRun(run.getId(), "script");
        assertThat(actual.getApprovalId()).isEqualTo(original == null ? null : original.getId());
        assertThat(actual.getStatus()).isEqualTo(terminal);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(terminal);
        assertThat(approvalRepository.count()).isEqualTo(approvalCount);
        if (foreign != null) {
            assertThat(approvalRepository.findById(foreign.getId()).orElseThrow().getRunId()).isEqualTo(other.getId());
        }
    }

    @Test
    void historicalScriptApprovalResumeFailsClosedWithoutExecutingScript() {
        Long version = publishNodes(List.of(node("script", "script-exec", Map.of())), List.of());
        WorkflowRun run = newRunningRun(version, "{}");
        run.setStatus("waiting");
        runRepository.saveAndFlush(run);
        WorkflowApproval approval = approvalFixture(run.getId(), "script", "script-exec");
        approval.setStatus("approved");
        approvalRepository.saveAndFlush(approval);
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId("wnr_" + java.util.UUID.randomUUID());
        row.setRunId(run.getId());
        row.setNodeKey("script");
        row.setNodeType("script-exec");
        row.setStatus("waiting");
        row.setApprovalId(approval.getId());
        nodeRunRepository.saveAndFlush(row);

        engine.resumeRun(run.getId());

        WorkflowRun failed = runRepository.findById(run.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("failed");
        assertThat(failed.getErrorMessage()).contains("SCRIPT_APPROVAL_RESUME_UNSUPPORTED");
        assertThat(stub(NodeType.SCRIPT_EXEC).calls).hasValue(0);
        assertThat(nodeRunRepository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("cancelled");
    }

    @Test
    void resumeRejectsOuterTransactionBeforeClaimOrExecutionEvenWhenItRollsBack() {
        WorkflowRun run = newRunningRun(1L, "{}");
        run.setStatus("waiting");
        runRepository.saveAndFlush(run);
        WorkflowNodeRun row = waitingAttempt(run.getId(), "gate");
        AtomicInteger executions = new AtomicInteger();
        WorkflowEngine testEngine = new WorkflowEngine(runRepository, nodeRunRepository, approvalRepository,
                versionRepository, workflowRepository, jobRepository, registry(), dagValidator, expressionEvaluator,
                attemptAllocator, auditLogService, new SensitiveDataFilter(), Optional.of(events::add),
                enginePool, retryScheduler, properties, objectMapper, transactionManager) {
            @Override public void resumeNodes(String id, List<String> keys) { executions.incrementAndGet(); }
        };
        events.clear();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isTrue();
            assertThatThrownBy(() -> testEngine.resumeRun(run.getId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("active transaction");
            assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
            assertThat(nodeRunRepository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
            assertThat(executions).hasValue(0);
            assertThat(events).isEmpty();
            tx.setRollbackOnly();
        });

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(nodeRunRepository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(executions).hasValue(0);
        assertThat(events).isEmpty();
    }

    @Test
    void resumeRollsBackRunAndFirstAttemptWhenSecondConditionalUpdateFails() {
        WorkflowRun run = newRunningRun(1L, "{}");
        run.setStatus("waiting");
        runRepository.saveAndFlush(run);
        WorkflowNodeRun first = waitingAttempt(run.getId(), "first");
        WorkflowNodeRun second = waitingAttempt(run.getId(), "second");
        AtomicInteger updates = new AtomicInteger();
        WorkflowNodeRunRepository failingRepo = (WorkflowNodeRunRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WorkflowNodeRunRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("resumeWaitingAttempt") && updates.incrementAndGet() == 2) {
                        return 0;
                    }
                    try {
                        return method.invoke(nodeRunRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        AtomicInteger executions = new AtomicInteger();
        WorkflowEngine testEngine = new WorkflowEngine(runRepository, failingRepo, approvalRepository,
                versionRepository, workflowRepository, jobRepository, registry(), dagValidator, expressionEvaluator,
                attemptAllocator, auditLogService, new SensitiveDataFilter(), Optional.of(events::add),
                enginePool, retryScheduler, properties, objectMapper, transactionManager) {
            @Override public void resumeNodes(String id, List<String> keys) { executions.incrementAndGet(); }
        };

        assertThatThrownBy(() -> testEngine.resumeRun(run.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("attempt resume conflict");
        assertThat(updates).hasValue(2);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(nodeRunRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(nodeRunRepository.findById(second.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(executions).hasValue(0);
    }

    @Test
    void resumeRejectsMultipleWaitingAttemptsForSameNode() {
        WorkflowRun run = newRunningRun(1L, "{}");
        run.setStatus("waiting");
        runRepository.saveAndFlush(run);
        WorkflowNodeRun first = waitingAttempt(run.getId(), "gate");
        WorkflowNodeRun second = new WorkflowNodeRun();
        second.setId("wnr_" + java.util.UUID.randomUUID());
        second.setRunId(run.getId());
        second.setNodeKey("gate");
        second.setNodeType("approval");
        second.setAttempt(2);
        second.setStatus("waiting");
        second.setApprovalId(first.getApprovalId());
        nodeRunRepository.saveAndFlush(second);
        engine.resumeRun(run.getId());
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(nodeRunRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(nodeRunRepository.findById(second.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
    }

    @Test
    void resumeRejectsWaitingAttemptSupersededByNewerAttempt() {
        WorkflowRun run = newRunningRun(1L, "{}");
        run.setStatus("waiting");
        runRepository.saveAndFlush(run);
        WorkflowNodeRun first = waitingAttempt(run.getId(), "gate");
        WorkflowNodeRun newer = new WorkflowNodeRun();
        newer.setId("wnr_" + java.util.UUID.randomUUID());
        newer.setRunId(run.getId());
        newer.setNodeKey("gate");
        newer.setNodeType("approval");
        newer.setAttempt(2);
        newer.setStatus("failed");
        nodeRunRepository.saveAndFlush(newer);
        engine.resumeRun(run.getId());
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(nodeRunRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(nodeRunRepository.findById(newer.getId()).orElseThrow().getStatus()).isEqualTo("failed");
    }

    @Test
    void resumeRunConflictDoesNotWakeAttempt() {
        WorkflowRun run = newRunningRun(1L, "{}");
        WorkflowNodeRun row = waitingAttempt(run.getId(), "gate");
        assertThatThrownBy(() -> engine.resumeRun(run.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("run resume conflict");
        assertThat(nodeRunRepository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("waiting");
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("running");
    }

    @Test
    void resumeDoesNotWakeWaitingAttemptAddedAfterSelection() {
        WorkflowRun run = newRunningRun(1L, "{}");
        run.setStatus("waiting");
        runRepository.saveAndFlush(run);
        WorkflowNodeRun selected = waitingAttempt(run.getId(), "selected");
        WorkflowApproval lateApproval = approvalFixture(run.getId(), "late", "approval");
        AtomicReference<String> lateId = new AtomicReference<>();
        WorkflowNodeRunRepository repo = (WorkflowNodeRunRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WorkflowNodeRunRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("resumeWaitingAttempt")) {
                        WorkflowNodeRun late = new WorkflowNodeRun();
                        late.setId("wnr_" + java.util.UUID.randomUUID());
                        late.setRunId(run.getId());
                        late.setNodeKey("late");
                        late.setNodeType("approval");
                        late.setStatus("waiting");
                        late.setApprovalId(lateApproval.getId());
                        nodeRunRepository.saveAndFlush(late);
                        lateId.set(late.getId());
                    }
                    try {
                        return method.invoke(nodeRunRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        AtomicReference<List<String>> executed = new AtomicReference<>();
        WorkflowEngine testEngine = new WorkflowEngine(runRepository, repo, approvalRepository,
                versionRepository, workflowRepository, jobRepository, registry(), dagValidator, expressionEvaluator,
                attemptAllocator, auditLogService, new SensitiveDataFilter(), Optional.of(events::add),
                enginePool, retryScheduler, properties, objectMapper, transactionManager) {
            @Override public void resumeNodes(String id, List<String> keys) { executed.set(keys); }
        };
        testEngine.resumeRun(run.getId());
        assertThat(executed.get()).containsExactly("selected");
        assertThat(nodeRunRepository.findById(selected.getId()).orElseThrow().getStatus()).isEqualTo("running");
        assertThat(nodeRunRepository.findById(lateId.get()).orElseThrow().getStatus()).isEqualTo("waiting");
    }

    @Test
    void conditionalResumeRequiresExactBindingAndDecidedMatchingApproval() {
        WorkflowRun run = newRunningRun(1L, "{}");
        WorkflowNodeRun row = waitingAttempt(run.getId(), "gate");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThat(tx.<Integer>execute(s -> nodeRunRepository.resumeWaitingAttempt(row.getId(), run.getId(), row.getApprovalId() + 1)))
                .isEqualTo(0);
        WorkflowApproval approval = approvalRepository.findById(row.getApprovalId()).orElseThrow();
        approval.setNodeType("script-exec");
        approvalRepository.saveAndFlush(approval);
        assertThat(tx.<Integer>execute(s -> nodeRunRepository.resumeWaitingAttempt(row.getId(), run.getId(), row.getApprovalId())))
                .isEqualTo(0);
        approval = approvalRepository.findById(row.getApprovalId()).orElseThrow();
        approval.setNodeType("approval");
        approval.setStatus("pending");
        approvalRepository.saveAndFlush(approval);
        assertThat(tx.<Integer>execute(s -> nodeRunRepository.resumeWaitingAttempt(row.getId(), run.getId(), row.getApprovalId())))
                .isEqualTo(0);
        approval = approvalRepository.findById(row.getApprovalId()).orElseThrow();
        approval.setStatus("approved");
        approvalRepository.saveAndFlush(approval);
        assertThat(tx.<Integer>execute(s -> nodeRunRepository.resumeWaitingAttempt(row.getId(), run.getId(), row.getApprovalId())))
                .isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> nodeRunRepository.resumeWaitingAttempt(row.getId(), run.getId(), row.getApprovalId())))
                .isEqualTo(0);
    }

    private WorkflowNodeRun waitingAttempt(String runId, String key) {
        WorkflowApproval approval = approvalFixture(runId, key, "approval");
        approval.setStatus("approved");
        approvalRepository.saveAndFlush(approval);
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId("wnr_" + java.util.UUID.randomUUID());
        row.setRunId(runId);
        row.setNodeKey(key);
        row.setNodeType("approval");
        row.setStatus("waiting");
        row.setApprovalId(approval.getId());
        return nodeRunRepository.saveAndFlush(row);
    }

    /** 构造恢复测试所需的历史审批，而非调用生产挂起入口。 */
    private WorkflowApproval approvalFixture(String runId, String key, String type) {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId(runId);
        approval.setNodeKey(key);
        approval.setNodeType(type);
        approval.setTitle("gate");
        approval.setExpiresAt(LocalDateTime.now().plusSeconds(60));
        return approvalRepository.saveAndFlush(approval);
    }

    @Test
    void recoveryAbortsPersistedNonRetryableFailureBeforePartialDownstream() {
        WorkflowNode failed = node("failed", "script-exec", Map.of());
        failed.setOnFailure("abort");
        WorkflowNode downstream = node("downstream", "wait", Map.of());
        downstream.setOnFailure("partial");
        WorkflowRun run = newRunningRun(publishNodes(List.of(failed, downstream),
                List.of(edge("failed", "downstream"))), "{}");
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId("wnr_recovery_" + java.util.UUID.randomUUID().toString().replace("-", ""));
        row.setRunId(run.getId());
        row.setNodeKey("failed");
        row.setNodeType("script-exec");
        row.setAttempt(1);
        row.setStatus("failed");
        row.setErrorMessage("SCRIPT_RETRY_UNSAFE: interrupted after failure");
        row.setStartedAt(LocalDateTime.now());
        row.setFinishedAt(LocalDateTime.now());
        nodeRunRepository.saveAndFlush(row);

        engine.drive(run);

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("failed");
        assertThat(stub(NodeType.WAIT).calls.get()).isZero();
        assertThat(statusOf(run.getId(), "downstream")).isEqualTo("cancelled");
        assertThat(nodeRunRepository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("failed");
    }

    private WorkflowRun newRunningRun(Long versionId, String inputJson) {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_rd_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        run.setWorkflowId(workflowId);
        run.setVersionId(versionId);
        run.setVersionNo(1);
        run.setTriggerType("manual");
        run.setStatus("running");
        run.setStartedBy(42L);
        run.setStartedAt(LocalDateTime.now());
        run.setInputJson(inputJson);
        return runRepository.saveAndFlush(run);
    }

    private void saveSucceededNodeRun(String runId, String nodeKey, String nodeType, String outputJson) {
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId("wnr_rd_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        row.setRunId(runId);
        row.setNodeKey(nodeKey);
        row.setNodeType(nodeType);
        row.setAttempt(1);
        row.setStatus("succeeded");
        row.setOutputJson(outputJson);
        try {
            row.setOutputSha256(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(outputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        row.setStartedAt(LocalDateTime.now());
        row.setFinishedAt(LocalDateTime.now());
        nodeRunRepository.saveAndFlush(row);
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

        events.clear();
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
        WorkflowNodeRun zombie = new WorkflowNodeRun();
        zombie.setId("zombie-test");
        zombie.setRunId(runId);
        zombie.setNodeKey("check");
        zombie.setNodeType("script-exec");
        zombie.setAttempt(2);
        zombie.setStatus("running");
        zombie.setStartedAt(LocalDateTime.now().minusSeconds(600));
        zombie.setApprovalId(456L);
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
        assertThat(timedOut.getApprovalId()).isEqualTo(456L);
        // 看门狗把"为什么超时"写进 error_message（异常终态的原因字段）
        assertThat(timedOut.getErrorMessage()).contains("看门狗兜底");
        assertThat(events.stream().filter(e -> "NODE_FAILED".equals(e.type())
                && runId.equals(e.runId()) && "check".equals(e.nodeKey())))
                .anySatisfy(e -> { assertThat(e.message()).isNull(); assertThat(e.payload()).isNull(); });
    }

    @Test
    @DisplayName("历史脚本失败或看门狗超时不可自动重试，即使 task 绑定未知")
    void historicalScriptFailuresNeverBecomeReady() {
        WorkflowNode script = node("script", "script-exec", Map.of());
        script.setRetryMax(3);
        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(List.of(script));
        def.setEdges(List.of());
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setNodeKey("script");
        row.setAttempt(1);
        for (String status : List.of("failed", "timeout")) {
            row.setStatus(status);
            for (String taskId : java.util.Arrays.asList(null, "existing-task")) {
                row.setTaskId(taskId);
                assertThat(DagScheduler.findReady(def, Map.of("script", row), n -> true)).isEmpty();
                assertThat(DagScheduler.hasPendingRetry(def, Map.of("script", row))).isFalse();
            }
        }
    }

    @Test
    @DisplayName("真实脚本执行器超时后保留绑定 task 且不重派")
    void scriptTimeoutDoesNotRedispatchBoundTask() {
        properties.getEngine().setDefaultNodeTimeoutSec(1);
        properties.getEngine().setPollIntervalMs(100);
        TaskService dispatch = mock(TaskService.class);
        AtomicInteger dispatches = new AtomicInteger();
        when(dispatch.createAndDispatch(any(), any(), any(), any())).thenAnswer(invocation -> {
            int count = dispatches.incrementAndGet();
            Task task = new Task();
            task.setId("script-timeout-" + count);
            task.setName("script timeout");
            task.setTotalCount(1);
            return taskRepository.saveAndFlush(task);
        });
        var config = mock(com.easyshell.server.ai.config.AgenticConfigService.class);
        lenient().when(config.getStringSet(anyString(), eq(Set.of()))).thenReturn(Set.of());
        lenient().when(config.getStringList(anyString(), eq(List.of()))).thenReturn(List.of());
        var script = new ScriptExecNodeExecutor(dispatch, mock(ClusterService.class), mock(TagService.class),
                agentRepository, jobRepository, taskRepository, approvalRepository, nodeRunRepository,
                runRepository, entityManager, approvalStore, expressionEvaluator, new com.easyshell.server.ai.risk.CommandRiskEngine(config),
                dagValidator, new SensitiveDataFilter(), objectMapper, properties, transactionManager);
        ReflectionTestUtils.setField(script, "provenanceService", provenanceService);
        WorkflowEngine realScriptEngine = new WorkflowEngine(runRepository, nodeRunRepository, approvalRepository,
                versionRepository, workflowRepository, jobRepository, new NodeExecutorRegistry(List.of(script)),
                dagValidator, expressionEvaluator, attemptAllocator, auditLogService, new SensitiveDataFilter(),
                Optional.empty(), enginePool, retryScheduler, properties, objectMapper, transactionManager);
        WorkflowRunService realScriptService = new WorkflowRunService(workflowRepository, versionRepository,
                runRepository, nodeRunRepository, approvalRepository, realScriptEngine, auditLogService, properties,
                objectMapper, transactionManager);
        ReflectionTestUtils.setField(realScriptService, "provenanceService", provenanceService);
        Long version = publishDefinition("""
                {"schemaVersion":1,"nodes":[{"key":"script","type":"script-exec",
                 "retryMax":2,"retryIntervalSec":1,"timeoutSec":1,
                 "config":{"script":"echo ok","targets":{"mode":"explicit","agentIds":["ag-1"]}}}],"edges":[]}
                """);
        WorkflowRun run = realScriptService.startRun(workflowId, version, Map.of(), 42L, null, "manual");
        assertThat(awaitTerminal(run.getId()).getStatus()).isEqualTo("failed");
        sleepQuietly(1500);
        var rows = nodeRunRepository.findByRunId(run.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTaskId()).isEqualTo("script-timeout-1");
        assertThat(rows.get(0).getErrorMessage()).startsWith("NODE_TIMEOUT:").contains("禁止自动重派");
        assertThat(dispatches).hasValue(1);
        assertThat(taskRepository.findById("script-timeout-1")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARAM_MISSING", "RISK_BLOCKED", "AI_APPROVAL_REQUIRED", "AGENT_DISPATCH_FAILED"})
    @DisplayName("脚本失败保留原始错误码、脱敏并禁止自动重派")
    void scriptFailurePreservesErrorCode(String code) {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            throw new NodeFailedException(code, "password=secret-test-value");
        };
        WorkflowRun run = startRun("""
                {"schemaVersion":1,"nodes":[{"key":"script","type":"script-exec",
                 "retryMax":2,"retryIntervalSec":1,"config":{}}],"edges":[]}
                """, Map.of());
        assertThat(awaitTerminal(run.getId()).getStatus()).isEqualTo("failed");
        var rows = nodeRunRepository.findByRunId(run.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getErrorMessage()).startsWith(code + ":")
                .contains("禁止自动重派").doesNotContain("secret-test-value", "SCRIPT_RETRY_UNSAFE");
        assertThat(stub(NodeType.SCRIPT_EXEC).calls).hasValue(1);
    }

    @Test
    void watchdogClosesStaleAttemptAfterRunTerminalWithoutResumingIt() {
        stub(NodeType.SCRIPT_EXEC).behavior = ctx -> {
            ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(),
                    Map.of("maxPcent", 93.2));
            return NodeOutcome.SUCCEEDED;
        };
        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        assertThat(awaitTerminal(run.getId()).getStatus()).as(dumpRun()).isEqualTo("succeeded");
        WorkflowNodeRun zombie = new WorkflowNodeRun();
        zombie.setId("zombie-terminal-test");
        zombie.setRunId(run.getId());
        zombie.setNodeKey("check");
        zombie.setNodeType("script-exec");
        zombie.setAttempt(2);
        zombie.setStatus("running");
        zombie.setStartedAt(LocalDateTime.now().minusSeconds(600));
        zombie.setApprovalId(789L);
        nodeRunRepository.saveAndFlush(zombie);

        events.clear();
        watchdog.scanOnce();

        WorkflowNodeRun closed = nodeRunRepository.findById(zombie.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo("cancelled");
        assertThat(closed.getApprovalId()).isEqualTo(789L);
        assertThat(closed.getCancelDelivered()).isFalse();
        assertThat(closed.getErrorMessage()).contains("外部执行可能仍在运行");
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("succeeded");
        assertThat(events.stream().filter(e -> "NODE_CANCELLED".equals(e.type())
                && run.getId().equals(e.runId()) && "check".equals(e.nodeKey())))
                .singleElement().satisfies(e -> {
                    assertThat(e.message()).isNull();
                    assertThat(e.payload()).isNull();
                });
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
        jdbc.execute("CREATE TABLE IF NOT EXISTS host_identity (agent_id VARCHAR(64) PRIMARY KEY, generation BIGINT NOT NULL, active TINYINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_target_snapshot (task_id VARCHAR(64) PRIMARY KEY, target_count INT NOT NULL, completed TINYINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_target_snapshot_item (task_id VARCHAR(64) NOT NULL, agent_id VARCHAR(64) NOT NULL, generation BIGINT NOT NULL, PRIMARY KEY (task_id, agent_id), FOREIGN KEY (task_id) REFERENCES task_target_snapshot(task_id))");
        jdbc.update("DELETE FROM host_identity WHERE agent_id = ?", "ag-cancel");
        jdbc.update("INSERT INTO host_identity (agent_id, generation, active) VALUES (?, ?, ?)", "ag-cancel", 1L, 1);
        TaskTargetSnapshotRepository targetSnapshots = new TaskTargetSnapshotRepository(jdbc);
        HostAclService acl = mock(HostAclService.class);
        lenient().when(acl.allowedForGeneration(42L, "ag-cancel", 1L, HostAclService.Permission.EXECUTE)).thenReturn(true);
        TaskService taskService = new com.easyshell.server.service.impl.TaskServiceImpl(
                taskRepository, jobRepository, mock(com.easyshell.server.security.ScriptAccess.class), agentRepository, counterService,
                logHandler, agentHandler, mock(ClusterService.class), mock(TagService.class), riskEngine,
                auditLogService, List.of(mock(DispatchEvidenceVerifier.class)), acl,
                new HostIdentityService(jdbc, agentRepository), targetSnapshots);

        Agent agent = new Agent();
        agent.setId("ag-cancel");
        agent.setHostname("cancel-host");
        agent.setIp("127.0.0.1");
        agent.setStatus(1);
        agentRepository.save(agent);

        WorkflowRun run = startRun(linearDefinition("skip"), Map.of("threshold", 90));
        awaitTerminal(run.getId());
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId("late-test");
        row.setRunId(run.getId());
        row.setNodeKey("late");
        row.setNodeType("script-exec");
        row.setAttempt(1);
        row.setStatus("running");
        row.setStartedAt(LocalDateTime.now());
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("late-task");
        request.setScriptContent("echo late");
        request.setAgentIds(List.of("ag-cancel"));
        request.setTimeoutSeconds(60);
        // createAndDispatch 里要注册 afterCommit 回调，必须放在事务里跑
        TransactionTemplate dispatchTx = new TransactionTemplate(transactionManager);
        Task task = dispatchTx.execute(tx -> taskService.createAndDispatch(request, 42L));
        assertThat(targetSnapshots.findComplete(task.getId()).orElseThrow().targets())
                .containsExactly(new com.easyshell.server.model.dto.TaskTargetSnapshot.Target("ag-cancel", 1L));
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
