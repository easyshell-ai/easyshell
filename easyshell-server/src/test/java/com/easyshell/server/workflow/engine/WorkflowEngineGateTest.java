package com.easyshell.server.workflow.engine;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("WorkflowEngine.evaluateGate — classifier 动态 handle（default / fail-closed）")
class WorkflowEngineGateTest {

    private WorkflowEngine engine;
    private WorkflowDefinition definition;
    private VariableStore store;
    private Map<String, WorkflowNodeRun> latest;
    private ExecutorService pool;
    private WorkflowRunRepository runs;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(objectMapper);
        pool = Executors.newSingleThreadExecutor();
        runs = mock(WorkflowRunRepository.class);
        engine = new WorkflowEngine(runs,
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

    @Test
    void cleanupWhileLockedCannotCreateSecondLockOrEnterStaleRun() throws Exception {
        WorkflowRun run = new WorkflowRun();
        run.setId("same-run");
        run.setStatus("running");
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        Method withLock = WorkflowEngine.class.getDeclaredMethod("withLock", WorkflowRun.class, Runnable.class);
        Method cleanup = WorkflowEngine.class.getDeclaredMethod("cleanup", String.class);
        withLock.setAccessible(true);
        cleanup.setAccessible(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger entries = new AtomicInteger();
        Future<?> first = pool.submit(() -> {
            try {
                withLock.invoke(engine, run, (Runnable) () -> {
                    entries.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                });
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            cleanup.invoke(engine, run.getId());
            withLock.invoke(engine, run, (Runnable) entries::incrementAndGet);
            assertThat(entries.get()).isEqualTo(1);
        } finally {
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
        run.setStatus("cancelled");
        withLock.invoke(engine, run, (Runnable) entries::incrementAndGet);
        assertThat(entries.get()).isEqualTo(1);
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
    @DisplayName("class 不在配置类别：所有边 SKIP")
    void unknownClassFailsClosed() throws Exception {
        store.putNodeOutput("classify", null, Map.of("class", "storage"));
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
        assertThat(gate("default_branch")).isEqualTo("SKIP");
    }

    @Test
    @DisplayName("class 字段缺失：所有边 SKIP")
    void missingClassFailsClosed() throws Exception {
        store.putNodeOutput("classify", null, Map.of("reason", "no class"));
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
        assertThat(gate("default_branch")).isEqualTo("SKIP");
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

    @Test
    void skippedOrdinaryEdgesPropagateAndJoinAllowsSelectedPath() throws Exception {
        WorkflowNode a = node("a");
        WorkflowNode b = node("b");
        WorkflowNode join = node("join");
        definition.setNodes(List.of(a, b, join));
        definition.setEdges(List.of(edge("a", "b", null), edge("a", "join", null),
                edge("b", "join", null)));
        latest = Map.of("a", row("a", "skipped"));
        assertThat(gate("b")).isEqualTo("SKIP");
        latest = Map.of("a", row("a", "skipped"), "b", row("b", "skipped"));
        assertThat(gate("join")).isEqualTo("SKIP");
        latest = Map.of("a", row("a", "succeeded"), "b", row("b", "skipped"));
        assertThat(gate("join")).isEqualTo("PASS");
    }

    @Test
    void missingBranchHandleCannotUseLegacyConditionToBypassGate() throws Exception {
        store.putNodeOutput("classify", null, Map.of("class", "disk"));
        WorkflowEdge noHandle = edge("classify", "disk_branch", null);
        noHandle.setCondition("true");
        definition.setEdges(List.of(noHandle));
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
        definition.nodeByKey("classify").setType("condition");
        store.putNodeOutput("classify", null, Map.of("result", true));
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
    }

    @Test
    void legacyConditionResultComparisonStillGatesBothOutcomes() throws Exception {
        definition.nodeByKey("classify").setType("condition");
        WorkflowEdge legacy = edge("classify", "disk_branch", null);
        legacy.setCondition("nodes.classify.output.result == true");
        definition.setEdges(List.of(legacy));
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
        store.putNodeOutput("classify", null, Map.of("result", false));
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
        store.putNodeOutput("classify", null, Map.of("result", true));
        assertThat(gate("disk_branch")).isEqualTo("PASS");
        legacy.setCondition("nodes.classify.output.result == true || true");
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
    }

    @Test
    void failedBranchCannotUseStaleOutputEvenWithPartial() throws Exception {
        definition.nodeByKey("classify").setType("condition");
        WorkflowEdge branch = edge("classify", "disk_branch", "false");
        branch.setOnUpstreamFailure("partial");
        definition.setEdges(List.of(branch));
        store.putNodeOutput("classify", null, Map.of("result", false));
        latest = Map.of("classify", row("classify", "failed"));
        assertThat(gate("disk_branch")).isEqualTo("SKIP");
    }

    @Test
    void downstreamWaitsForRetryAndUsesLatestAttempt() throws Exception {
        WorkflowNode source = node("source");
        source.setRetryMax(1);
        WorkflowNode target = node("target");
        definition.setNodes(List.of(source, target));
        definition.setEdges(List.of(edge("source", "target", null)));
        WorkflowNodeRun failed = row("source", "failed");
        latest = Map.of("source", failed);
        Method method = WorkflowEngine.class.getDeclaredMethod("dagSchedulerFindReady",
                WorkflowDefinition.class, Map.class, String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<WorkflowNode> ready = (List<WorkflowNode>) method.invoke(engine, definition, latest, "run");
        assertThat(ready).extracting(WorkflowNode::getKey).containsExactly("source");
        WorkflowNodeRun succeeded = row("source", "succeeded");
        succeeded.setAttempt(2);
        latest = DagScheduler.latestByKey(List.of(failed, succeeded));
        assertThat(gate("target")).isEqualTo("PASS");
    }

    private WorkflowNodeRun row(String key, String status) {
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setNodeKey(key);
        row.setAttempt(1);
        row.setStatus(status);
        return row;
    }
}
