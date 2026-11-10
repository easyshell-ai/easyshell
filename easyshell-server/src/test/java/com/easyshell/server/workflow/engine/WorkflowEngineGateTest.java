package com.easyshell.server.workflow.engine;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("WorkflowEngine.evaluateGate — classifier 动态 handle（default / fail-closed）")
class WorkflowEngineGateTest {

    private WorkflowEngine engine;
    private WorkflowDefinition definition;
    private VariableStore store;
    private Map<String, WorkflowNodeRun> latest;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(objectMapper);
        pool = Executors.newSingleThreadExecutor();
        engine = new WorkflowEngine(mock(WorkflowRunRepository.class),
                mock(WorkflowNodeRunRepository.class), mock(WorkflowApprovalRepository.class),
                mock(WorkflowVersionRepository.class), mock(WorkflowRepository.class),
                mock(JobRepository.class), mock(NodeExecutorRegistry.class),
                new DagValidator(evaluator), evaluator, mock(AttemptAllocator.class),
                mock(AuditLogService.class), new SensitiveDataFilter(), Optional.empty(), pool,
                mock(org.springframework.scheduling.TaskScheduler.class), new WorkflowProperties(),
                objectMapper, mock(PlatformTransactionManager.class));

        WorkflowNode classify = new WorkflowNode();
        classify.setKey("classify");
        classify.setType("classifier");
        classify.setConfig(new LinkedHashMap<>(Map.of(
                "input", "x",
                "classes", List.of(Map.of("name", "disk"), Map.of("name", "network")))));

        WorkflowNode disk = node("disk_branch");
        WorkflowNode fallback = node("default_branch");
        definition = new WorkflowDefinition();
        definition.setNodes(List.of(classify, disk, fallback));
        definition.setEdges(List.of(
                edge("classify", "disk_branch", "disk"),
                edge("classify", "default_branch", "default")));

        WorkflowNodeRun upstream = new WorkflowNodeRun();
        upstream.setRunId("wfr_gate");
        upstream.setNodeKey("classify");
        upstream.setAttempt(1);
        upstream.setStatus("succeeded");
        latest = Map.of("classify", upstream);
        store = new VariableStore();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private WorkflowNode node(String key) {
        WorkflowNode node = new WorkflowNode();
        node.setKey(key);
        node.setType("transform");
        node.setConfig(new LinkedHashMap<>());
        return node;
    }

    private WorkflowEdge edge(String from, String to, String handle) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(from + "->" + to);
        edge.setFrom(from);
        edge.setTo(to);
        edge.setSourceHandle(handle);
        return edge;
    }

    /** 反射调用私有 evaluateGate，返回 PASS/SKIP/CANCEL */
    private String gate(String targetNodeKey) throws Exception {
        Method method = WorkflowEngine.class.getDeclaredMethod("evaluateGate",
                WorkflowDefinition.class, WorkflowNode.class, Map.class, VariableStore.class);
        method.setAccessible(true);
        Object decision = method.invoke(engine, definition, definition.nodeByKey(targetNodeKey),
                latest, store);
        Method action = decision.getClass().getDeclaredMethod("action");
        action.setAccessible(true);
        return String.valueOf(action.invoke(decision));
    }

    @Test
    @DisplayName("class 精确匹配：class handle PASS，default SKIP")
    void exactClassMatches() throws Exception {
        store.putNodeOutput("classify", null, Map.of("class", "disk"));
        assertThat(gate("disk_branch")).isEqualTo("PASS");
        assertThat(gate("default_branch")).isEqualTo("SKIP");
    }

    @Test
    @DisplayName("class 不在配置类别：class handle SKIP，default PASS")
    void unknownClassFallsBackToDefault() throws Exception {
        store.putNodeOutput("classify", null, Map.of("class", "storage"));
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
        assertThat(gate("default_branch")).isEqualTo("PASS");
    }

    @Test
    @DisplayName("class 字段缺失：class handle SKIP，default PASS")
    void missingClassFallsBackToDefault() throws Exception {
        store.putNodeOutput("classify", null, Map.of("reason", "no class"));
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
        assertThat(gate("default_branch")).isEqualTo("PASS");
    }

    @Test
    @DisplayName("分类器输出整体缺失：所有 handle 都不通行（fail-closed）")
    void missingOutputFailsClosed() throws Exception {
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
        assertThat(gate("default_branch")).isEqualTo("SKIP");
    }

    @Test
    @DisplayName("class 不是字符串：视为不可解析，所有 handle 都不通行")
    void unparseableClassFailsClosed() throws Exception {
        store.putNodeOutput("classify", null, Map.of("class", 42));
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
        assertThat(gate("default_branch")).isEqualTo("SKIP");
    }
}
