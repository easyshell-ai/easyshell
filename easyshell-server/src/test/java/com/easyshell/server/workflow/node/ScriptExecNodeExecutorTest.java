package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.config.AgenticConfigService;
import com.easyshell.server.ai.risk.CommandRiskEngine;
import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.DispatchEvidenceVerifier;
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
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * script-exec 节点的风险门禁（设计要求：工作流里的脚本执行必须过 {@code createAndDispatch}
 * 这道唯一咽喉；HIGH/MEDIUM 只有"已通过的 approval 祖先"能授权派发）。
 *
 * <p>驱动<b>真实</b>的 {@link CommandRiskEngine} 与 {@code TaskServiceImpl}，断言：
 * ① 高风险/封禁脚本在无审批授权时节点失败且<b>不落 task 行</b>；
 * ② 参数值注入在 L1 就被拒，走不到派发；
 * ③ LOW 脚本正常派发（task.source=workflow + execToken + agent_versions 快照）；
 * ④ 已审批的 approval 祖先以 HUMAN_APPROVED 授权 HIGH 脚本并写 SCRIPT_DISPATCH 审计；
 * ⑤ MEDIUM 脚本按 riskPolicy 转审批或失败。</p>
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
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AuditLogService auditLogService;

    private ObjectMapper objectMapper;
    private ExpressionEvaluator expressionEvaluator;
    private WorkflowApprovalStore approvalStore;
    private WorkflowProperties properties;
    private TaskService taskService;
    private ScriptExecNodeExecutor executor;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        taskRepository.deleteAll();
        agentRepository.deleteAll();
        approvalRepository.deleteAll();

        objectMapper = new ObjectMapper();
        expressionEvaluator = new ExpressionEvaluator(objectMapper);
        approvalStore = new WorkflowApprovalStore(approvalRepository, transactionManager);
        properties = new WorkflowProperties();
        properties.getEngine().setDefaultNodeTimeoutSec(300);
        properties.getEngine().setPollIntervalMs(50);

        AgenticConfigService config = mock(AgenticConfigService.class);
        lenient().when(config.getStringSet(anyString(), eq(Set.of()))).thenReturn(Set.of());
        lenient().when(config.getStringList(anyString(), eq(List.of()))).thenReturn(List.of());
        CommandRiskEngine riskEngine = new CommandRiskEngine(config);

        TaskLogWebSocketHandler logHandler = mock(TaskLogWebSocketHandler.class);
        AgentWebSocketHandler agentHandler = mock(AgentWebSocketHandler.class);
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
        taskService = new com.easyshell.server.service.impl.TaskServiceImpl(
                taskRepository, jobRepository, mock(ScriptRepository.class), agentRepository,
                counterService, logHandler, agentHandler, mock(ClusterService.class),
                mock(TagService.class), riskEngine, auditLogService,
                List.of(new com.easyshell.server.workflow.gate.WorkflowApprovalEvidenceVerifier(
                        approvalRepository), mock(DispatchEvidenceVerifier.class)));

        executor = new ScriptExecNodeExecutor(taskService, mock(ClusterService.class),
                mock(TagService.class), agentRepository, jobRepository, taskRepository,
                approvalRepository, approvalStore, expressionEvaluator, riskEngine,
                new DagValidator(expressionEvaluator), new SensitiveDataFilter(), objectMapper,
                properties, transactionManager);

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
        return nodeRun;
    }

    private NodeContext context(WorkflowRun run, WorkflowNodeRun nodeRun, WorkflowNode node,
                                VariableStore variables) {
        return context(run, nodeRun, node, variables, new AtomicBoolean());
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
        assertThat(variables.resolve("nodes.clean.output")).isPresent();
    }

    @Test
    @DisplayName("已审批的 approval 祖先授权 HIGH 脚本：HUMAN_APPROVED 派发 + SCRIPT_DISPATCH 审计")
    void approvedAncestorAuthorizesHighRiskScript() {
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
        Long approvalId = approvalRepository.saveAndFlush(approval).getId();

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

        NodeOutcome outcome = executor.execute(ctx);

        assertThat(outcome).isEqualTo(NodeOutcome.SUCCEEDED);
        assertThat(nodeRun.getTaskId()).startsWith("task_");
        // 豁免必须留痕：authority + 证据 + 等级（不存脚本原文）
        verify(auditLogService).logSync(eq(42L), any(), eq("SCRIPT_DISPATCH"), eq("task"), isNull(),
                contains("authority=HUMAN_APPROVED"), isNull(), eq("success"));
        verify(auditLogService).logSync(eq(42L), any(), eq("SCRIPT_DISPATCH"), eq("task"), isNull(),
                contains("workflow_approval:" + approvalId), isNull(), eq("success"));
    }

    @Test
    @DisplayName("MEDIUM 脚本 + allow_medium：转审批挂起（WAITING），detail 不存脚本原文")
    void mediumScriptTurnsIntoApproval() {
        WorkflowRun run = newRun();
        WorkflowNodeRun nodeRun = newNodeRun(run, "wnr-gate-6");

        NodeOutcome outcome = executor.execute(context(run, nodeRun,
                node("find /var/log -name '*.log' -delete", Map.of("riskPolicy", "allow_medium")),
                new VariableStore()));

        assertThat(outcome).isEqualTo(NodeOutcome.WAITING);
        assertThat(nodeRun.getApprovalId()).isNotNull();
        WorkflowApproval created = approvalRepository.findById(nodeRun.getApprovalId()).orElseThrow();
        assertThat(created.getStatus()).isEqualTo("pending");
        assertThat(created.getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(created.getDetailJson()).doesNotContain("find /var/log");
        assertThat(taskRepository.count()).isZero();
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
