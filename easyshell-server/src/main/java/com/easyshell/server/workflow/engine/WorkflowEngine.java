package com.easyshell.server.workflow.engine;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.model.FailurePolicy;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowEdge;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 工作流执行引擎（§3 执行内核，M1 <b>同步阻塞实现</b>，不走 Redis/补偿）。
 *
 * <p><b>算法来源</b>：{@code ai/orchestrator/DagExecutor.executeDag} 的拓扑就绪计算 +
 * 并发主循环照搬（ Kahn 环检测 → findReady → 一波并发执行 → join → 波末 checkpoint →
 * abort 判定），外围加持久化、版本化、失败策略、attempt 语义与取消传播。
 * {@code ai/orchestrator/} 下一行未改（AI Chat 零回归）。</p>
 *
 * <p><b>线程与事务边界（§3.2）</b>：{@code drive()} 永远不跑在 HTTP 线程上（触发端点
 * submit 后立即返回）；节点跑在 {@code workflow-engine} 池；{@code startAttempt} /
 * {@code finishAttempt} 各是独立短事务；波末 {@code checkpoint()} 单事务；
 * <b>明确禁止</b>在 drive() 循环上开 @Transactional、在持有 DB 行锁时派发 agent。</p>
 *
 * <p><b>幂等三重（§3.5）</b>：① 触发 {@code uk_wfr_idem(user_id, idempotency_key)}；
 * ② 节点 {@code uk_node_run(run_id, node_key, attempt)} + startAttempt 捕获重复键异常；
 * ③ 脚本 {@code execToken = runId:nodeKey:attempt} 写进 Task.name，
 * {@code reportJobResult} 已按 job 终态幂等。消息重投不重复执行。</p>
 */
@Slf4j
@Component
public class WorkflowEngine {

    private static final List<String> TERMINAL_RUN = List.of("succeeded", "failed", "cancelled", "timeout");
    private static final List<String> ACTIVE_RUN = List.of("pending", "queued", "running", "waiting");
    private static final Set<String> NODE_TERMINAL = Set.of("succeeded", "failed", "skipped", "cancelled", "timeout");
    private static final Set<String> NODE_FAILED = Set.of("failed", "timeout", "cancelled");

    private final WorkflowRunRepository runRepository;
    private final WorkflowNodeRunRepository nodeRunRepository;
    private final WorkflowApprovalRepository approvalRepository;
    private final WorkflowVersionRepository versionRepository;
    private final JobRepository jobRepository;
    private final NodeExecutorRegistry registry;
    private final DagValidator dagValidator;
    private final ExpressionEvaluator expressionEvaluator;
    private final AttemptAllocator attemptAllocator;
    private final AuditLogService auditLogService;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final Consumer<WorkflowEvent> eventSink;
    private final ExecutorService enginePool;
    private final TaskScheduler retryScheduler;
    private final WorkflowProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 状态迁移的短事务模板（PROPAGATION_REQUIRED）。
     *
     * <p><b>为什么不用 @Transactional 包住 drive()</b>：设计 §3.2.2 明确禁止——
     * drive() 循环里要调 createAndDispatch 派发 agent，"持有 DB 行锁时调用 agent 派发"
     * 是硬禁。因此每个状态迁移点（checkpoint / finalize / cancel / resume）各自开一个
     * 短事务，节点执行体（可能跑几分钟）完全在事务之外。</p>
     */
    private final TransactionTemplate transactionTemplate;

    /** per-runId 进程内锁（MF-10②：触发端点/扫描器/看门狗/resume 四方同 JVM 重入防护） */
    private final Map<String, ReentrantLock> runLocks = new ConcurrentHashMap<>();

    /** per-run 取消标志：同 JVM 内执行中节点的检查点源（§3.10） */
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** per-run 变量存储（同 JVM 内跨波共享；M1 重启即失败，无需从 DB 重建） */
    private final Map<String, VariableStore> variableStores = new ConcurrentHashMap<>();

    /** 退避重试到期时刻：key = runId + ":" + nodeKey（M1 进程内，重启即失败故不落库） */
    private final Map<String, Instant> retryDue = new ConcurrentHashMap<>();

    /** tryLock 失败被登记的 run（由 10s 扫描器兜底，§3.8 v3 补充：不静默丢弃） */
    private final Set<String> pendingWakeups = ConcurrentHashMap.newKeySet();

    public WorkflowEngine(WorkflowRunRepository runRepository,
                          WorkflowNodeRunRepository nodeRunRepository,
                          WorkflowApprovalRepository approvalRepository,
                          WorkflowVersionRepository versionRepository,
                          JobRepository jobRepository,
                          NodeExecutorRegistry registry,
                          DagValidator dagValidator,
                          ExpressionEvaluator expressionEvaluator,
                          AttemptAllocator attemptAllocator,
                          AuditLogService auditLogService,
                          SensitiveDataFilter sensitiveDataFilter,
                          Optional<Consumer<WorkflowEvent>> eventSink,
                          @org.springframework.beans.factory.annotation.Qualifier("workflowEnginePool") ExecutorService enginePool,
                          @org.springframework.beans.factory.annotation.Qualifier("workflowScheduler") TaskScheduler retryScheduler,
                          WorkflowProperties properties,
                          ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.approvalRepository = approvalRepository;
        this.versionRepository = versionRepository;
        this.jobRepository = jobRepository;
        this.registry = registry;
        this.dagValidator = dagValidator;
        this.expressionEvaluator = expressionEvaluator;
        this.attemptAllocator = attemptAllocator;
        this.auditLogService = auditLogService;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.eventSink = eventSink.orElseGet(WorkflowEventPublisher::new);
        this.enginePool = enginePool;
        this.retryScheduler = retryScheduler;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ==================================================================
    // 入口
    // ==================================================================

    /** 提交到引擎池执行（触发端点/恢复路径都用它，绝不在 HTTP 线程上跑 drive） */
    public void submitDrive(WorkflowRun run) {
        enginePool.submit(() -> {
            try {
                drive(run);
            } catch (Exception e) {
                log.error("drive() 未预期异常: run={}", run.getId(), e);
                cancelRun(run, "failed", "engine_internal");
            }
        });
    }

    /** 同 JVM 事件入口（审批决策 afterCommit 调用，§3.8 唤醒路径①） */
    public void submitResume(String runId) {
        enginePool.submit(() -> resumeRun(runId));
    }

    /**
     * 唤醒：DB 行唯一事实源 + 同 JVM 事件 + 10s 扫描兜底（§3.8）。
     *
     * <p>本 run 下全部 waiting 节点都已决策才唤醒（漏一个都整体放弃，等下一轮扫描）；
     * {@code resumeIfWaiting} 影响 0 行 = run 已被取消/已终态，节点唤醒仍然安全——
     * 因为 {@link #resumeNodes} 会按当前行状态二次过滤，且 run 终态时直接返回。</p>
     */
    public void resumeRun(String runId) {
        WorkflowRun run = runRepository.findById(runId).orElse(null);
        if (run == null || TERMINAL_RUN.contains(run.getStatus())) {
            return;
        }
        List<WorkflowNodeRun> waiting = nodeRunRepository.findByRunIdAndStatus(runId, "waiting");
        if (waiting.isEmpty()) {
            return;
        }
        List<String> decidedKeys = new ArrayList<>();
        for (WorkflowNodeRun row : waiting) {
            Optional<com.easyshell.server.model.entity.WorkflowApproval> approval =
                    approvalRepository.findByRunIdAndNodeKey(runId, row.getNodeKey());
            if (approval.isEmpty() || "pending".equals(approval.get().getStatus())) {
                return;   // 还有未决策的审批：整体放弃，等下一轮
            }
            decidedKeys.add(row.getNodeKey());
        }
        transactionTemplate.execute(status -> {
            runRepository.resumeIfWaiting(runId);          // run: waiting -> running（幂等）
            nodeRunRepository.resumeWaitingNodes(runId);   // node_run: waiting -> running
            return null;
        });
        resumeNodes(runId, decidedKeys);
    }

    /**
     * resume 第二步：把被唤醒的节点重新执行（<b>沿用既有 attempt 行，不新开 attempt</b>）。
     *
     * <p>为什么不让 drive() 的 findReady 去捡这些节点：它们的状态是 running（不是 pending），
     * 而 attempt 号必须保持不变——审计链上"这一次 attempt 包含等待审批的时间"。</p>
     */
    public void resumeNodes(String runId, List<String> nodeKeys) {
        WorkflowRun run = runRepository.findById(runId).orElse(null);
        if (run == null || TERMINAL_RUN.contains(run.getStatus())) {
            return;
        }
        withLock(run, () -> {
            WorkflowDefinition def = loadDefinition(run.getVersionId());
            if (def == null) {
                return;
            }
            Map<String, WorkflowNodeRun> latest = latestByKey(runId);
            // 只重执行"刚被唤醒且现在确实是 running"的节点：取消/超时抢先的行不再执行
            List<WorkflowNode> resumed = nodeKeys.stream()
                    .filter(key -> {
                        WorkflowNodeRun row = latest.get(key);
                        return row != null && "running".equals(row.getStatus());
                    })
                    .map(def::nodeByKey).filter(Objects::nonNull).toList();
            AtomicBoolean cancelled = cancelFlags.computeIfAbsent(runId, k -> new AtomicBoolean());
            if (!resumed.isEmpty()) {
                runWave(run, def, resumed, latest, cancelled, true, new AtomicBoolean());
                checkpoint(run, def);
            }
            if (postWave(run, def, cancelled)) {
                driveLocked(run);
            }
        });
    }

    /** 主循环（同步阻塞；测试直接调用） */
    public void drive(WorkflowRun run) {
        withLock(run, () -> driveLocked(run));
    }

    private void withLock(WorkflowRun run, Runnable body) {
        ReentrantLock lock = runLocks.computeIfAbsent(run.getId(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            // 同一 run 已有执行者（触发端点/扫描器/看门狗/resume 之一）：放弃本次，
            // 登记后由 10s 扫描器兜底——不静默丢弃（v2 小瑕，v3 修复）
            pendingWakeups.add(run.getId());
            return;
        }
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    private void driveLocked(WorkflowRun run) {
        WorkflowDefinition def = loadDefinition(run.getVersionId());
        if (def == null) {
            failRun(run, "definition_missing");
            return;
        }
        if (dagValidator.hasCycle(def)) {
            failRun(run, "workflow.graph.cycle");
            return;
        }
        String runId = run.getId();
        AtomicBoolean cancelled = cancelFlags.computeIfAbsent(runId, k -> new AtomicBoolean());

        while (!cancelled.get()) {
            WorkflowRun current = runRepository.findById(runId).orElse(null);
            if (current == null || TERMINAL_RUN.contains(current.getStatus())) {
                cleanup(runId);
                return;
            }
            if (Boolean.TRUE.equals(current.getCancelRequested())) {
                cancelRun(current, "cancelled", "user_cancelled");
                return;
            }

            Map<String, WorkflowNodeRun> latest = latestByKey(runId);
            List<WorkflowNode> ready = dagSchedulerFindReady(def, latest, runId);
            if (ready.isEmpty()) {
                if (DagScheduler.hasPendingRetry(def, latest)) {
                    // 还有节点在退避等待：保持 running + 心跳，由到期的 scheduler 任务重新 drive
                    checkpoint(current, def);
                    return;
                }
                break;
            }

            AtomicBoolean attemptConflict = new AtomicBoolean(false);
            runWave(current, def, ready, latest, cancelled, false, attemptConflict);
            checkpoint(current, def);
            if (attemptConflict.get()) {
                cancelRun(current, "failed", "attempt_conflict");
                return;
            }
            if (!postWave(current, def, cancelled)) {
                return;
            }
        }
        finalizeRun(run);
    }

    // ==================================================================
    // 波与节点
    // ==================================================================

    private void runWave(WorkflowRun run, WorkflowDefinition def, List<WorkflowNode> ready,
                         Map<String, WorkflowNodeRun> latest, AtomicBoolean cancelled,
                         boolean resume, AtomicBoolean attemptConflict) {
        // 背压②：单波不超过 max-concurrent-nodes，其余留到下一波（§3.9）
        int limit = Math.max(1, properties.getEngine().getMaxConcurrentNodes());
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (WorkflowNode node : ready.stream().limit(limit).toList()) {
            if (cancelled.get()) {
                break;
            }
            futures.add(CompletableFuture.runAsync(
                    () -> runNode(run, def, node, latest, cancelled, resume, attemptConflict), enginePool));
        }
        for (CompletableFuture<Void> future : futures) {
            try {
                future.join();
            } catch (CompletionException | java.util.concurrent.CancellationException e) {
                log.warn("节点执行 future 异常: run={} err={}", run.getId(), e.getMessage());
            }
        }
    }

    private void runNode(WorkflowRun run, WorkflowDefinition def, WorkflowNode node,
                         Map<String, WorkflowNodeRun> latest, AtomicBoolean cancelled,
                         boolean resume, AtomicBoolean attemptConflict) {
        String runId = run.getId();
        VariableStore store = variableStores.computeIfAbsent(runId, k -> new VariableStore());

        // ① 取消检查点
        if (cancelled.get()) {
            WorkflowNodeRun existing = latest.get(node.getKey());
            if (existing != null && "running".equals(existing.getStatus())) {
                finishNode(store, run, existing, "cancelled", "user_cancelled", "用户取消", null);
            } else {
                insertTerminalRow(runId, node, "cancelled", "user_cancelled");
            }
            return;
        }

        // ② 边门控 + 上游失败策略（resume 的节点已过门控）
        if (!resume) {
            GateDecision gate = evaluateGate(def, node, latest, store);
            if (gate.action() == GateAction.SKIP) {
                insertTerminalRow(runId, node, "skipped", gate.reason());
                publish(WorkflowEvent.of("NODE_SKIPPED", runId, node.getKey(), gate.reason()));
                return;
            }
            if (gate.action() == GateAction.CANCEL) {
                insertTerminalRow(runId, node, "cancelled", gate.reason());
                publish(WorkflowEvent.of("NODE_CANCELLED", runId, node.getKey(), gate.reason()));
                return;
            }
        }

        // ③ attempt 抢占（uk_node_run 兜底，MF-10）
        WorkflowNodeRun row;
        if (resume) {
            row = latest.get(node.getKey());
            if (row == null) {
                row = startAttemptSafely(run, node, attemptConflict);
                if (row == null) {
                    return;
                }
            }
        } else {
            row = startAttemptSafely(run, node, attemptConflict);
            if (row == null) {
                return;
            }
        }

        publish(WorkflowEvent.of("NODE_STARTED", runId, node.getKey(), "attempt=" + row.getAttempt()));
        long startedAtMillis = System.currentTimeMillis();

        NodeExecutor executor = registry.executorFor(node.nodeType());
        if (executor == null) {
            finishNode(store, run, row, "failed", "NODE_TYPE_UNKNOWN",
                    "节点类型无执行器: " + node.getType(), null);
            return;
        }

        AtomicBoolean nodeCancelled = new AtomicBoolean(false);
        try {
            NodeContext ctx = new NodeContext(run, row, node, def, store, enginePool, cancelled,
                    nodeCancelled, false, eventSink, store);
            NodeOutcome outcome = executeWithTimeout(executor, ctx, node);
            switch (outcome) {
                case SUCCEEDED -> finishNode(store, run, row, "succeeded", null, null, null);
                case SKIPPED -> finishNode(store, run, row, "skipped", null, null, null);
                case WAITING -> {
                    // 执行体已落 approval 行；node_run 由引擎挂起（状态迁移单写入方）
                    int suspended = attemptAllocator.suspendAttempt(row, row.getApprovalId());
                    if (suspended == 0) {
                        finishNode(store, run, row, "cancelled", "cancel_race",
                                "挂起前已被取消/超时", null);
                    } else {
                        publish(WorkflowEvent.of("NODE_WAITING", runId, node.getKey(),
                                "等待审批 approval=" + row.getApprovalId()));
                    }
                }
                case FAILED -> finishNode(store, run, row, "failed", "NODE_FAILED", "节点返回失败", null);
            }
        } catch (NodeCancelledException e) {
            finishNode(store, run, row, "cancelled", "user_cancelled", e.getMessage(), null);
        } catch (NodeFailedException e) {
            handleFailure(run, def, node, row, executor, store, e.getCode(), e.getMessage(), startedAtMillis);
        } catch (ExpressionException e) {
            handleFailure(run, def, node, row, executor, store, e.getCode(), e.getMessage(), startedAtMillis);
        } catch (BusinessException e) {
            // 门禁 449 等：错误信息可能含等级/模式串，但不含脚本原文（C-9）
            handleFailure(run, def, node, row, executor, store, "RISK_BLOCKED", e.getMessage(), startedAtMillis);
        } catch (Exception e) {
            log.error("节点执行未预期异常: run={} node={}", runId, node.getKey(), e);
            handleFailure(run, def, node, row, executor, store, "NODE_INTERNAL",
                    "节点内部错误: " + e.getClass().getSimpleName(), startedAtMillis);
        }
    }

    private WorkflowNodeRun startAttemptSafely(WorkflowRun run, WorkflowNode node, AtomicBoolean attemptConflict) {
        try {
            return attemptAllocator.startAttempt(run.getId(), node.getKey(), node.getType());
        } catch (AttemptConflictException e) {
            log.error("attempt 抢占失败，终止 run: {}", e.getMessage());
            attemptConflict.set(true);
            auditLogService.logSync(run.getStartedBy(), "SYSTEM", "WORKFLOW_ATTEMPT_CONFLICT",
                    "workflow_node_run", run.getId() + ":" + node.getKey(), e.getMessage(), null, "error");
            return null;
        }
    }

    private void handleFailure(WorkflowRun run, WorkflowDefinition def, WorkflowNode node, WorkflowNodeRun row,
                               NodeExecutor executor, VariableStore store, String code, String message,
                               long startedAtMillis) {
        int attempt = row.getAttempt() == null ? 1 : row.getAttempt();
        int allowed = node.retryMaxOrDefault(0) + 1;
        boolean retryable = executor.retryable(new NodeFailedException(code, message)) || defaultRetryable(code);
        if (retryable && attempt < allowed) {
            finishNode(store, run, row, "failed", code, message + "（attempt " + attempt + " 失败，将退避重试）", null);
            scheduleRetry(run, node, attempt);
            return;
        }
        finishNode(store, run, row, "failed", code, message, null);
    }

    /** 默认可重试判定（§3.12：超时/网络/IO/agent 掉线可重试；类型/校验/BANNED/权限不可重试） */
    private boolean defaultRetryable(String code) {
        return "NODE_TIMEOUT".equals(code) || "NODE_INTERNAL".equals(code)
                || "AGENT_OFFLINE".equals(code) || "AGENT_DISPATCH_FAILED".equals(code);
    }

    private void scheduleRetry(WorkflowRun run, WorkflowNode node, int attempt) {
        int interval = node.retryIntervalSecOrDefault(properties.getEngine().getRetryIntervalSec());
        long backoff = Math.min(300L, interval * (1L << Math.min(10, attempt - 1)));
        // ±20% jitter（§3.12）
        double jitterFactor = java.util.concurrent.ThreadLocalRandom.current().nextDouble() - 0.5;
        long delay = Math.max(1L, backoff + Math.round(backoff * 0.2 * jitterFactor));
        String key = run.getId() + ":" + node.getKey();
        retryDue.put(key, Instant.now().plusSeconds(delay));
        retryScheduler.schedule(() -> {
            retryDue.remove(key);
            submitDrive(run);
        }, Instant.now().plusSeconds(delay));
        log.info("节点 {} 将在 {}s 后重试（attempt={}）", node.getKey(), delay, attempt);
    }

    private NodeOutcome executeWithTimeout(NodeExecutor executor, NodeContext ctx, WorkflowNode node) throws Exception {
        int timeoutSec = node.timeoutSecOrDefault(properties.getEngine().getDefaultNodeTimeoutSec());
        CompletableFuture<NodeOutcome> future = CompletableFuture.supplyAsync(() -> executor.execute(ctx), enginePool);
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 节点级超时（§3.10 ①）：orphan 线程可能还在跑，翻节点级取消标志让它下一轮退出；
            // 迟到结果由 finishAttemptAtomically 的 status='running' 条件丢弃
            ctx.nodeCancelled().set(true);
            throw new NodeFailedException("NODE_TIMEOUT", "节点执行超过 " + timeoutSec + " 秒");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new NodeFailedException("NODE_INTERNAL", "节点执行失败: " + cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeCancelledException("节点执行被中断");
        }
    }

    // ==================================================================
    // 边门控
    // ==================================================================

    private enum GateAction {PASS, SKIP, CANCEL}

    private record GateDecision(GateAction action, String reason) {
    }

    private GateDecision evaluateGate(WorkflowDefinition def, WorkflowNode node,
                                      Map<String, WorkflowNodeRun> latest, VariableStore store) {
        List<WorkflowEdge> inEdges = def.inEdges(node.getKey());
        if (inEdges.isEmpty()) {
            return new GateDecision(GateAction.PASS, null);   // 入口节点
        }
        boolean anyTraversable = false;
        boolean anyUnsettled = false;
        boolean anyUpstreamFailed = false;
        for (WorkflowEdge edge : inEdges) {
            WorkflowNodeRun upstream = latest.get(edge.getFrom());
            String status = upstream == null ? "pending" : upstream.getStatus();
            if (!NODE_TERMINAL.contains(status)) {
                anyUnsettled = true;
                continue;
            }
            if (NODE_FAILED.contains(status)) {
                anyUpstreamFailed = true;
                FailurePolicy policy = FailurePolicyResolver.resolve(edge, node, def);
                if (policy == FailurePolicy.ABORT) {
                    return new GateDecision(GateAction.CANCEL, "upstream_failed");
                }
                if (policy == FailurePolicy.SKIP) {
                    continue;   // 本边不可通行
                }
                // PARTIAL：边可通行，失败上游的 output 视为不存在
            }
            if (edge.getCondition() != null && !edge.getCondition().isBlank()) {
                boolean pass;
                try {
                    pass = expressionEvaluator.evaluate(edge.getCondition(), store);
                } catch (ExpressionException e) {
                    // fail-closed：边条件不可求值 ⇒ 该边不可通行（下游被跳过而不是被放行）
                    store.addWarning("边条件求值失败（" + e.getCode() + "），边未通行: "
                            + edge.getFrom() + " -> " + node.getKey());
                    continue;
                }
                if (!pass) {
                    continue;
                }
            }
            anyTraversable = true;
        }
        if (anyTraversable) {
            return new GateDecision(GateAction.PASS, null);
        }
        // 上游未终态在 findReady 之后不该发生；防御性按 skip 处理，避免无行节点造成热循环。
        // 上游失败导致的跳过记 upstream_failed（§2.8 表），纯边条件不成立记 condition_false
        if (anyUpstreamFailed) {
            return new GateDecision(GateAction.SKIP, "upstream_failed");
        }
        return new GateDecision(GateAction.SKIP, anyUnsettled ? "upstream_not_settled" : "condition_false");
    }

    // ==================================================================
    // 波末与终态
    // ==================================================================

    /**
     * 波后判定。
     *
     * @return true = 主循环继续；false = 已终态/已挂起/已终止，调用方应返回
     */
    private boolean postWave(WorkflowRun run, WorkflowDefinition def, AtomicBoolean cancelled) {
        String runId = run.getId();
        WorkflowRun current = runRepository.findById(runId).orElse(null);
        if (current == null || TERMINAL_RUN.contains(current.getStatus())) {
            cleanup(runId);
            return false;
        }
        if (Boolean.TRUE.equals(current.getCancelRequested())) {
            cancelRun(current, "cancelled", "user_cancelled");
            return false;
        }
        List<WorkflowNodeRun> rows = nodeRunRepository.findByRunId(runId);
        Map<String, WorkflowNodeRun> latest = DagScheduler.latestByKey(rows);

        // ① 本节点自身失败且 onFailure=abort → 终止 run（重试额度未尽的除外）
        for (WorkflowNode node : def.getNodes()) {
            WorkflowNodeRun row = latest.get(node.getKey());
            if (row == null || !NODE_FAILED.contains(row.getStatus())) {
                continue;
            }
            int attempt = row.getAttempt() == null ? 1 : row.getAttempt();
            if (attempt < node.retryMaxOrDefault(0) + 1) {
                continue;   // 还要重试，不终止
            }
            if (FailurePolicyResolver.ownFailure(node, def) == FailurePolicy.ABORT) {
                // 节点原因与 run 原因分开记：下游看到的是"上游失败"，run 看到的是"谁终止了整条 run"
                cancelRun(current, "failed", "upstream_failed", "node_failed:" + node.getKey());
                return false;
            }
        }

        // ② 审批被拒且 definition.onRejection=abort（§3.8 边界③）
        if ("abort".equals(def.onRejectionOrAbort())) {
            boolean rejected = rows.stream().anyMatch(r -> "failed".equals(r.getStatus())
                    && r.getErrorMessage() != null && r.getErrorMessage().startsWith("approval_rejected"));
            if (rejected) {
                cancelRun(current, "failed", "approval_rejected");
                return false;
            }
        }

        // ③ 派生式挂起（MF-9）：本波全部节点都已终态或 waiting，才允许把 run 置 waiting
        boolean anyWaiting = rows.stream().anyMatch(r -> "waiting".equals(r.getStatus()));
        if (anyWaiting && rows.stream().allMatch(
                r -> NODE_TERMINAL.contains(r.getStatus()) || "waiting".equals(r.getStatus()))) {
            Integer suspended = transactionTemplate.execute(status -> runRepository.suspendIfRunning(runId));
            if (suspended != null && suspended == 1) {
                publish(WorkflowEvent.of("RUN_WAITING", runId, null, "等待审批"));
                return false;
            }
            // 影响 0 行 = run 已不是 running（被取消/并发迁移）：交给 10s 扫描器二次判定
        }
        return true;
    }

    /** 波末单事务：心跳 + 当前波节点 + 标量变量全量快照（§3.7） */
    private void checkpoint(WorkflowRun run, WorkflowDefinition def) {
        VariableStore store = variableStores.get(run.getId());
        VariableStore.Snapshot snapshot = store != null
                ? store.snapshot() : new VariableStore.Snapshot("{}", false);
        List<String> runningKeys = nodeRunRepository.findByRunIdAndStatus(run.getId(), "running").stream()
                .map(WorkflowNodeRun::getNodeKey).toList();
        String runId = run.getId();
        String currentNodes = toJson(runningKeys);
        String variablesJson = snapshot.json();
        boolean truncated = snapshot.truncated();
        transactionTemplate.execute(status -> {
            runRepository.checkpointAtomically(runId, LocalDateTime.now(), currentNodes, variablesJson, truncated);
            return null;
        });
    }

    private void finalizeRun(WorkflowRun run) {
        transactionTemplate.execute(status -> {
            finalizeRunInTransaction(run);
            return null;
        });
    }

    private void finalizeRunInTransaction(WorkflowRun run) {
        String runId = run.getId();
        WorkflowRun current = runRepository.findById(runId).orElse(null);
        if (current == null || TERMINAL_RUN.contains(current.getStatus())) {
            cleanup(runId);
            return;
        }
        WorkflowDefinition def = loadDefinition(current.getVersionId());
        // 未开始节点补 skipped(not_reached) 留痕
        if (def != null) {
            Map<String, WorkflowNodeRun> latest = latestByKey(runId);
            for (WorkflowNode node : def.getNodes()) {
                if (!latest.containsKey(node.getKey())) {
                    insertTerminalRow(runId, node, "skipped", "not_reached");
                }
            }
        }
        Map<String, WorkflowNodeRun> latest = latestByKey(runId);
        boolean anyFailed = latest.values().stream().anyMatch(r -> NODE_FAILED.contains(r.getStatus()));
        String status = anyFailed ? "failed" : "succeeded";
        String error = latest.values().stream()
                .filter(r -> NODE_FAILED.contains(r.getStatus()))
                .map(WorkflowNodeRun::getErrorMessage)
                .filter(Objects::nonNull).findFirst().orElse(null);
        long duration = current.getStartedAt() == null ? 0L
                : Duration.between(current.getStartedAt(), LocalDateTime.now()).toMillis();
        int finished = runRepository.finishRunAtomically(runId, status, LocalDateTime.now(), duration,
                buildRunOutput(latest), truncate(error, 1000), TERMINAL_RUN);
        if (finished == 1) {
            publish(WorkflowEvent.of(anyFailed ? "RUN_FAILED" : "RUN_SUCCEEDED", runId, null,
                    anyFailed ? truncate(error, 200) : "运行完成"));
        }
        cleanup(runId);
    }

    private String buildRunOutput(Map<String, WorkflowNodeRun> latest) {
        Map<String, Object> output = new LinkedHashMap<>();
        long total = 0;
        for (WorkflowNodeRun row : latest.values()) {
            if (row.getOutputJson() == null) {
                continue;
            }
            output.put(row.getNodeKey(), row.getOutputJson());
            total += row.getOutputJson().length();
            if (total > 65536) {
                output.put("_truncated", "输出摘要超过 64KB，已停止汇总");
                break;
            }
        }
        return toJson(output);
    }

    private void failRun(WorkflowRun run, String reason) {
        transactionTemplate.execute(status -> {
            long duration = run.getStartedAt() == null ? 0L
                    : Duration.between(run.getStartedAt(), LocalDateTime.now()).toMillis();
            runRepository.finishRunAtomically(run.getId(), "failed", LocalDateTime.now(), duration,
                    null, truncate(reason, 1000), TERMINAL_RUN);
            return null;
        });
        publish(WorkflowEvent.of("RUN_FAILED", run.getId(), null, reason));
        cleanup(run.getId());
    }

    // ==================================================================
    // 取消
    // ==================================================================

    /**
     * 用户取消（B10 诚实处理：agent 无 cancel 消息，已派发作业只能<b>逻辑取消</b>）。
     *
     * <p>取消 = "我不等了"，不是"它停了"：job.status 置 5(cancelled)，agent 跑完后回传的
     * result 被 {@code TaskServiceImpl.reportJobResult} 的 {@code job.getStatus() >= 2}
     * 检查丢弃——"取消后不再接受该 job 回传"由此保证，且不需要改 agent 协议（X4）。</p>
     */
    public void requestCancel(String runId, Long userId) {
        transactionTemplate.execute(status -> {
            int marked = runRepository.requestCancelAtomically(runId);
            if (marked == 0) {
                return null;   // 已取消/已终态/不存在：幂等返回
            }
            // 同 JVM 内执行中的节点：翻转取消标志，下一检查点（轮询循环每轮一次）退出
            AtomicBoolean flag = cancelFlags.get(runId);
            if (flag != null) {
                flag.set(true);
            }
            WorkflowRun run = runRepository.findById(runId).orElse(null);
            if (run == null) {
                return null;
            }
            List<String> taskIds = nodeRunRepository.findByRunId(runId).stream()
                    .map(WorkflowNodeRun::getTaskId).filter(Objects::nonNull).distinct().toList();
            // 取消必须留痕：操作人 + runId + 被取消时各节点的 task_id 清单（§3.10 ⑤）
            auditLogService.logSync(userId, "SYSTEM", "WORKFLOW_RUN_CANCEL", "workflow_run", runId,
                    "task_ids=" + taskIds, null, "success");
            cancelRunInTransaction(run, "cancelled", "user_cancelled", "user_cancelled");
            return null;
        });
    }

    /**
     * 取消/终止传播（§3.10 取消传播矩阵 + §2.8 ABORT 惰性传播）。
     *
     * @param runStatus run 终态：cancelled（用户取消）/ failed（节点失败、审批拒绝、attempt 冲突）/ timeout
     * @param nodeReason 未开始与等待中节点的取消原因
     */
    public void cancelRun(WorkflowRun run, String runStatus, String nodeReason) {
        cancelRun(run, runStatus, nodeReason, nodeReason);
    }

    /**
     * @param runStatus  run 终态：cancelled / failed / timeout
     * @param nodeReason 未开始与等待中节点的取消原因（审计与 UI 展示"为什么没执行"）
     * @param runError   run 行 error_message（"是哪个节点/什么事件终止了整条 run"）
     */
    public void cancelRun(WorkflowRun run, String runStatus, String nodeReason, String runError) {
        transactionTemplate.execute(status -> {
            cancelRunInTransaction(run, runStatus, nodeReason, runError);
            return null;
        });
    }

    private void cancelRunInTransaction(WorkflowRun run, String runStatus, String nodeReason, String runError) {
        String runId = run.getId();
        WorkflowRun current = runRepository.findById(runId).orElse(null);
        if (current == null) {
            cleanup(runId);
            return;
        }
        if (TERMINAL_RUN.contains(current.getStatus())) {
            cleanup(runId);
            return;
        }
        long duration = current.getStartedAt() == null ? 0L
                : Duration.between(current.getStartedAt(), LocalDateTime.now()).toMillis();
        int finished = runRepository.finishRunAtomically(runId, runStatus, LocalDateTime.now(), duration,
                null, truncate(runError, 1000), TERMINAL_RUN);
        if (finished == 0) {
            cleanup(runId);
            return;   // 已有其他线程写过终态（取消 vs 恢复的竞态由此确定收敛）
        }

        // ① 未开始与等待中的节点批量取消
        nodeRunRepository.cancelUnfinishedNodes(runId, nodeReason);

        // ② 从未开始的节点补一行 cancelled 留痕（运行详情页能看到"为什么没执行"）
        WorkflowDefinition def = loadDefinition(current.getVersionId());
        if (def != null) {
            Map<String, WorkflowNodeRun> latest = latestByKey(runId);
            for (WorkflowNode node : def.getNodes()) {
                if (!latest.containsKey(node.getKey())) {
                    insertTerminalRow(runId, node, "cancelled", nodeReason);
                }
            }
        }

        // ③ 已派发 job 逻辑取消：结果回传被 job 终态检查丢弃
        cancelDispatchedJobs(runId);

        // ④ 悬死审批清理（SF-7）
        approvalRepository.cancelPendingByRunId(runId);

        publish(WorkflowEvent.of("cancelled".equals(runStatus) ? "RUN_CANCELLED" : "RUN_FAILED",
                runId, null, runError));
        cleanup(runId);
    }

    private void cancelDispatchedJobs(String runId) {
        LocalDateTime now = LocalDateTime.now();
        Set<String> taskIds = nodeRunRepository.findByRunId(runId).stream()
                .map(WorkflowNodeRun::getTaskId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        for (String taskId : taskIds) {
            for (Job job : jobRepository.findByTaskId(taskId)) {
                if (job.getStatus() != null && job.getStatus() < 2) {
                    int cancelled = jobRepository.finalizeJobAtomically(job.getId(), 5, null,
                            "[SYSTEM] 工作流运行已取消：作业无法远程中止，将在主机侧继续执行完毕，其结果会被丢弃", now);
                    if (cancelled == 1) {
                        log.info("job {} 已逻辑取消（agent 侧无 cancel 消息，X4）", job.getId());
                    }
                }
            }
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private List<WorkflowNode> dagSchedulerFindReady(WorkflowDefinition def,
                                                     Map<String, WorkflowNodeRun> latest, String runId) {
        return DagScheduler.findReady(def, latest, node -> {
            Instant due = retryDue.get(runId + ":" + node.getKey());
            return due == null || !Instant.now().isBefore(due);
        });
    }

    /** finishNode：attempt 终态写入 + 变量状态刷新 + 事件 */
    private void finishNode(VariableStore store, WorkflowRun run, WorkflowNodeRun row, String status,
                            String errorCode, String errorMessage, String warningMessage) {
        VariableStore.StoredOutput output = store.nodeOutput(row.getNodeKey());
        List<String> warnings = store.drainWarnings();
        String warning = warningMessage;
        if (!warnings.isEmpty()) {
            warning = (warning == null ? "" : warning + "；") + String.join("；", warnings);
        }
        long duration = row.getStartedAt() == null ? 0L
                : Duration.between(row.getStartedAt(), LocalDateTime.now()).toMillis();
        String error = errorCode == null ? null
                : truncate(errorCode + ": " + (errorMessage == null ? "" : errorMessage), 1000);

        // run 已终态（取消/超时抢先）时，执行体即便返回 succeeded 也只记 cancelled——
        // 取消传播矩阵（§3.10）要求"执行中的节点 → cancelled"，不依赖执行体自觉检查取消标志
        String effectiveStatus = status;
        if (("succeeded".equals(status) || "skipped".equals(status))
                && TERMINAL_RUN.contains(runRepository.findById(run.getId())
                        .map(WorkflowRun::getStatus).orElse(null))) {
            effectiveStatus = "cancelled";
            error = "run 已终态，节点结果作废";
        }
        int updated = attemptAllocator.finishAttempt(row, AttemptAllocator.AttemptResult.builder()
                .status(effectiveStatus)
                .outputJson(output == null ? null : truncate(output.json(), 262144))
                .outputSha256(output == null ? null : output.sha256())
                .previewSha256(output == null ? null : output.previewSha256())
                .rawParams(row.getRawParams())
                .artifactIds(row.getArtifactIds())
                .errorMessage(truncate(error, 1000))
                .warningMessage(truncate(sensitiveDataFilter.filter(warning), 1000))
                .agentVersions(row.getAgentVersions())
                .taskId(row.getTaskId())
                .approvalId(row.getApprovalId())
                .cancelDelivered(false)
                .durationMs(duration)
                .build());
        if (updated == 0) {
            log.info("迟到结果被丢弃（attempt 已终态或被并发收尾）: run={} node={} attempt={}",
                    run.getId(), row.getNodeKey(), row.getAttempt());
            return;
        }
        store.putNodeStatus(row.getNodeKey(), effectiveStatus, duration, row.getAttempt());
        publish(WorkflowEvent.of(switch (effectiveStatus) {
            case "succeeded" -> "NODE_SUCCEEDED";
            case "skipped" -> "NODE_SKIPPED";
            case "cancelled" -> "NODE_CANCELLED";
            default -> "NODE_FAILED";
        }, run.getId(), row.getNodeKey(), effectiveStatus.equals("succeeded") ? null : error));
    }

    /** 从未开始节点的终态留痕（uk_node_run 冲突时静默放弃：说明已有 attempt 行） */
    private void insertTerminalRow(String runId, WorkflowNode node, String status, String reason) {
        LocalDateTime now = LocalDateTime.now();
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId("wnr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        row.setRunId(runId);
        row.setNodeKey(node.getKey());
        row.setNodeType(node.getType());
        row.setAttempt(1);
        row.setStatus(status);
        row.setErrorMessage(truncate(reason, 1000));
        row.setStartedAt(now);
        row.setFinishedAt(now);
        try {
            nodeRunRepository.save(row);
        } catch (Exception e) {
            log.debug("终态留痕写入被并发抢先（可忽略）: run={} node={} status={}", runId, node.getKey(), status);
        }
    }

    private Map<String, WorkflowNodeRun> latestByKey(String runId) {
        return DagScheduler.latestByKey(nodeRunRepository.findByRunId(runId));
    }

    private WorkflowDefinition loadDefinition(Long versionId) {
        Optional<String> json = versionRepository.findDefinition(versionId);
        if (json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json.get(), WorkflowDefinition.class);
        } catch (Exception e) {
            log.error("定义反序列化失败: versionId={} err={}", versionId, e.getMessage());
            return null;
        }
    }

    /** run 级进程内状态清理（终态后调用，防止 Map 无限增长） */
    private void cleanup(String runId) {
        cancelFlags.remove(runId);
        variableStores.remove(runId);
        runLocks.remove(runId);
        retryDue.entrySet().removeIf(e -> e.getKey().startsWith(runId + ":"));
        pendingWakeups.remove(runId);
    }

    /** 被 tryLock 拒绝而登记的 run（10s 扫描器消费） */
    public java.util.Set<String> pendingWakeups() {
        return pendingWakeups;
    }

    public VariableStore variableStore(String runId) {
        return variableStores.computeIfAbsent(runId, k -> new VariableStore());
    }

    /** 触发器用：把 run 级输入灌进变量存储（标量通道的唯一入口） */
    public void initVariables(WorkflowRun run, WorkflowDefinition def, Map<String, Object> input) {
        VariableStore store = variableStore(run.getId());
        store.putRunMetadata("run.id", run.getId());
        store.putRunMetadata("run.workflowName", run.getWorkflowId());
        store.putRunMetadata("run.startedBy", run.getStartedBy());
        store.putRunMetadata("run.triggerType", run.getTriggerType());
        if (input != null) {
            input.forEach((key, value) -> store.putInput(key, value));
        }
    }

    private void publish(WorkflowEvent event) {
        if (eventSink != null) {
            try {
                eventSink.accept(event);
            } catch (Exception e) {
                log.warn("事件出口异常: {}", e.getMessage());
            }
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
