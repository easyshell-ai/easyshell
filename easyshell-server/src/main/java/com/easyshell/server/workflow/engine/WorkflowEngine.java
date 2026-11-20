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
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.State;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.DagValidator;
import com.easyshell.server.workflow.model.FailurePolicy;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowEdge;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
    private final com.easyshell.server.repository.WorkflowRepository workflowRepository;
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

    /** 固定分片，锁身份不会随终态清理改变；不同 run 可能争用同一分片。 */
    private final ReentrantLock[] runLocks = java.util.stream.IntStream.range(0, 256)
            .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);
    private final Set<String> deferredCleanup = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Map<String, String>> claimedAttempts = new ThreadLocal<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowRecoveryClaimService recoveryClaims;

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
                          com.easyshell.server.repository.WorkflowRepository workflowRepository,
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
        this.workflowRepository = workflowRepository;
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
     * <p>读取的 waiting 节点全部已决策才唤醒；run 或任一 attempt 条件更新失败时
     * 整个事务回滚，不执行节点。其他写入未共享锁，不能保证读取后没有新增 waiting。</p>
     */
    public void resumeRun(String runId) {
        // REQUIRED would otherwise join the caller's transaction: returning from the
        // claim would not mean it committed, yet resumeNodes could execute immediately.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("resumeRun cannot run inside an active transaction");
        }
        WorkflowRun candidate = runRepository.findById(runId).orElse(null);
        if (candidate == null || TERMINAL_RUN.contains(candidate.getStatus())) {
            return;
        }
        withLock(candidate, () -> resumeRunLocked(runId), () -> submitResume(runId));
    }

    private void resumeRunLocked(String runId) {
        Map<String, String> claimed = new LinkedHashMap<>();
        Map<String, RecoveryAttemptRef> refs = new LinkedHashMap<>();
        boolean durable = properties.getEngine().isDurableResumeEnabled();
        if (durable && recoveryClaims == null) throw new IllegalStateException("recovery service is unavailable");
        List<String> decidedKeys = transactionTemplate.execute(status -> {
            WorkflowRun run = (durable ? runRepository.lockById(runId) : runRepository.findById(runId)).orElse(null);
            if (run == null || TERMINAL_RUN.contains(run.getStatus())) {
                return List.of();
            }
            List<WorkflowNodeRun> waiting = nodeRunRepository.findByRunIdAndStatus(runId, "waiting");
            if (waiting.isEmpty()) {
                return List.of();
            }
            Map<String, WorkflowNodeRun> latest = latestByKey(runId);
            for (WorkflowNodeRun row : waiting) {
                WorkflowNodeRun newest = latest.get(row.getNodeKey());
                if (claimed.putIfAbsent(row.getNodeKey(), row.getId()) != null
                        || newest == null || !Objects.equals(newest.getId(), row.getId())) {
                    log.warn("拒绝不明确的审批 attempt: run={} node={}", runId, row.getNodeKey());
                    return List.of();
                }
                if (row.getApprovalId() == null) {
                    return List.of();
                }
                Optional<com.easyshell.server.model.entity.WorkflowApproval> approval =
                        approvalRepository.findById(row.getApprovalId());
                if (approval.isEmpty() || !Objects.equals(runId, approval.get().getRunId())
                        || !Objects.equals(row.getNodeKey(), approval.get().getNodeKey())
                        || !Objects.equals(row.getNodeType(), approval.get().getNodeType())
                        || approval.get().getStatus() == null
                        || !Set.of("approved", "rejected", "timeout", "cancelled").contains(approval.get().getStatus())) {
                    return List.of();
                }
            }
            if (durable) {
                refs.putAll(recoveryClaims.claimWaiting(runId, waiting));
                if (refs.size() != waiting.size()) {
                    throw new IllegalStateException("incomplete recovery batch: " + runId);
                }
                return List.copyOf(refs.keySet());
            }
            if (runRepository.resumeIfWaiting(runId) != 1) {
                throw new IllegalStateException("run resume conflict: " + runId);
            }
            List<String> keys = new ArrayList<>();
            for (WorkflowNodeRun row : waiting) {
                if (nodeRunRepository.resumeWaitingAttempt(row.getId(), runId, row.getApprovalId()) != 1) {
                    throw new IllegalStateException("attempt resume conflict: " + row.getId());
                }
                keys.add(row.getNodeKey());
            }
            return keys;
        });
        if (decidedKeys != null && !decidedKeys.isEmpty()) {
            if (durable) {
                resumeNodes(runId, decidedKeys, Map.copyOf(refs));
                return;
            }
            claimedAttempts.set(claimed);
            try {
                resumeNodes(runId, decidedKeys);
            } finally {
                claimedAttempts.remove();
            }
        }
    }

    /**
     * resume 第二步：把被唤醒的节点重新执行（<b>沿用既有 attempt 行，不新开 attempt</b>）。
     *
     * <p>为什么不让 drive() 的 findReady 去捡这些节点：它们的状态是 running（不是 pending），
     * 而 attempt 号必须保持不变——审计链上"这一次 attempt 包含等待审批的时间"。</p>
     */
    public void resumeNodes(String runId, List<String> nodeKeys) {
        resumeNodes(runId, nodeKeys, Map.of());
    }

    private void resumeNodes(String runId, List<String> nodeKeys, Map<String, RecoveryAttemptRef> refs) {
        boolean durable = properties.getEngine().isDurableResumeEnabled();
        Map<String, String> claimed = durable ? null : claimedAttempts.get();
        List<String> remaining = new ArrayList<>(nodeKeys.stream().distinct().toList());
        // These are deliberately separate from the work-list. A claim is not an
        // accepted submission, and an accepted submission must not be cleaned up
        // merely because its future has not started yet.
        Set<String> accepted = new java.util.HashSet<>();
        try {
        WorkflowRun run = runRepository.findById(runId).orElse(null);
        if (run == null || TERMINAL_RUN.contains(run.getStatus())) {
            return;
        }
        withLock(run, () -> {
            if (!initVariablesIfAbsentOrFail(run)) {
                return;
            }
            WorkflowDefinition def = loadDefinition(run.getVersionId());
            if (def == null) {
                failRun(run, "definition_missing: 无法加载工作流定义");
                return;
            }
            Map<String, WorkflowNodeRun> latest = latestByKey(runId);
            WorkflowRun currentRun = runRepository.findById(runId).orElse(null);
            if (currentRun == null || TERMINAL_RUN.contains(currentRun.getStatus())) {
                return;
            }
            if (Boolean.TRUE.equals(currentRun.getCancelRequested())) {
                cancelRun(currentRun, "cancelled", "user_cancelled");
                return;
            }
            if (durable ? refs.isEmpty() : claimed == null) {
                // External callers cannot prove ownership of a running attempt.
                // A previously failed strict AI node must still terminate the run;
                // do not claim or rewrite any unowned running attempt here.
                abortFailedNode(currentRun, def, latest);
                log.warn("拒绝无领取凭据的恢复: run={}", runId);
                return;
            }
            if (abortFailedNode(currentRun, def, latest)) {
                return;
            }
            AtomicBoolean cancelled = cancelFlags.computeIfAbsent(runId, k -> new AtomicBoolean());
            // Process negative decisions before approvals; no later wave may run after rejection.
            Map<String, WorkflowNodeRun> selected = latest;
            remaining.sort(java.util.Comparator.comparingInt(key -> {
                WorkflowNodeRun row = selected.get(key);
                return row != null && row.getApprovalId() != null
                        && approvalRepository.findById(row.getApprovalId())
                                .map(a -> !"approved".equals(a.getStatus())).orElse(true) ? 0 : 1;
            }));
            Set<String> negative = remaining.stream().filter(key -> {
                WorkflowNodeRun row = selected.get(key);
                return row == null || row.getApprovalId() == null
                        || approvalRepository.findById(row.getApprovalId())
                                .map(a -> !"approved".equals(a.getStatus())).orElse(true);
            }).collect(java.util.stream.Collectors.toSet());
            int limit = Math.max(1, properties.getEngine().getMaxConcurrentNodes());
                while (!remaining.isEmpty()) {
                    WorkflowRun current = runRepository.findById(runId).orElse(null);
                    if (current == null || TERMINAL_RUN.contains(current.getStatus()) || cancelled.get()) {
                        return;
                    }
                    if (Boolean.TRUE.equals(current.getCancelRequested())) {
                        cancelRun(current, "cancelled", "user_cancelled");
                        return;
                    }
                    latest = latestByKey(runId);
                    if (abortFailedNode(current, def, latest)) {
                        return;
                    }
                    int waveSize = negative.contains(remaining.get(0)) ? 1 : Math.min(limit, remaining.size());
                    List<String> keys = new ArrayList<>(remaining.subList(0, waveSize));
                    List<WorkflowNode> wave = new ArrayList<>();
                    for (String key : keys) {
                        WorkflowNodeRun row = latest.get(key);
                        WorkflowNode node = def.nodeByKey(key);
                        boolean exactDispatch = node != null && node.nodeType() == NodeType.SCRIPT_EXEC
                                && properties.getApproval().isDispatchEnabled() && row != null
                                && validDispatchSuspension(row);
                        if (node != null && node.nodeType() == NodeType.SCRIPT_EXEC && !exactDispatch) {
                            cancelRun(current, "failed", "resume_unsupported",
                                    "SCRIPT_APPROVAL_RESUME_UNSUPPORTED: 历史脚本动态审批恢复暂不支持，请联系管理员处理");
                            return;
                        }
                        RecoveryAttemptRef ref = refs.get(key);
                        if (row == null || !(durable ? matchesRecovery(row, ref)
                                : Objects.equals(claimed.get(key), row.getId()))
                                || !"running".equals(row.getStatus()) || node == null
                                || (node.nodeType() != NodeType.APPROVAL && !exactDispatch)) {
                            cancelRun(current, "failed", "resume_attempt_invalid");
                            return;
                        }
                        wave.add(node);
                    }
                    try {
                        runWave(current, def, wave, latest, cancelled, true, new AtomicBoolean(),
                                key -> {
                                    accepted.add(key);
                                    remaining.remove(key);
                                 }, refs);
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        // runWave joins every successfully submitted future before propagating.
                        // Only keys still in remaining were never accepted by this pool.
                        cancelRun(current, "failed", "resume_dispatch_rejected");
                        return;
                    }
                    checkpoint(current, def);
                    if (!postWave(current, def, cancelled)) {
                        return;
                    }
                    if (durable && keys.stream().map(latestByKey(runId)::get)
                            .anyMatch(row -> row != null && "waiting".equals(row.getStatus()))) {
                        return;
                    }
                }
                driveLocked(run);
        });
        } finally {
            // The claim and terminal run transaction have committed before these independent
            // attempt transactions. A process crash between them remains unrecovered.
            // Only a claim for which runAsync threw before returning is undelivered.
            // In particular, do not close an accepted future that is still queued.
            if (durable) {
                for (RecoveryAttemptRef ref : refs.values()) {
                    // CAS only CLAIMED members. Accepted/ENTERED work can never be inferred unexecuted.
                    try {
                        attemptAllocator.closeRecoveryAborted(ref, false, ignored -> publish(WorkflowEvent.of(
                                "NODE_CANCELLED", runId, ref.member().nodeKey(), "resume_aborted")));
                        attemptAllocator.markRecoveryUncertain(ref);
                    } catch (RuntimeException cleanupFailure) {
                        log.error("恢复成员收尾失败，保留持久状态禁止重派: run={} node={}",
                                runId, ref.member().nodeKey(), cleanupFailure);
                    }
                }
            } else if (claimed != null) {
                Set<String> undelivered = new java.util.HashSet<>(claimed.keySet());
                undelivered.removeAll(accepted);
                for (String key : undelivered) {
                    String id = claimed.get(key);
                    if (id == null) continue;
                    WorkflowNodeRun row = nodeRunRepository.findById(id).orElse(null);
                    if (row == null) continue;
                    attemptAllocator.closeResumeAborted(runId, key, id, row.getAttempt(),
                            "resume_aborted: 恢复在执行此节点前中止",
                            ignored -> publish(WorkflowEvent.of("NODE_CANCELLED", runId, key, "resume_aborted")));
                }
            }
        }
    }

    private boolean matchesRecovery(WorkflowNodeRun row, RecoveryAttemptRef ref) {
        return ref != null && Objects.equals(row.getRunId(), ref.claim().runId())
                && Objects.equals(row.getId(), ref.member().nodeRunId())
                && Objects.equals(row.getNodeKey(), ref.member().nodeKey())
                && Objects.equals(row.getAttempt(), ref.member().attempt());
    }

    /** 主循环（同步阻塞；测试直接调用） */
    public void drive(WorkflowRun run) {
        // 队列泵回/扫描器重驱动不会经过 WorkflowRunService 的立即启动路径：在引擎入口
        // 兜底播种 run.input.* 与 run 元信息，保证 queued 启动的 run 与立即启动的完全等价
        withLock(run, () -> {
            if (initVariablesIfAbsentOrFail(run)) {
                driveLocked(run);
            }
        }, () -> submitDrive(run));
    }

    private void withLock(WorkflowRun run, Runnable body) {
        // Direct resumeNodes callers cannot safely replay an already-running attempt.
        withLock(run, body, null);
    }

    private void withLock(WorkflowRun run, Runnable body, Runnable retry) {
        ReentrantLock lock = lockFor(run.getId());
        if (!lock.tryLock()) {
            // Retry the original operation, not the approval-only pendingWakeups scanner.
            // A resume retry occurs before any waiting -> running transition.
            if (retry != null) {
                try {
                    retryScheduler.schedule(retry, Instant.now().plusSeconds(1));
                } catch (RuntimeException e) {
                    // No attempt has been claimed on this path. Scanner may retry later.
                    log.error("重试调度失败，保留原状态等待扫描: run={}", run.getId(), e);
                }
            } else {
                log.warn("拒绝重放未持锁的 running attempt: run={}", run.getId());
            }
            return;
        }
        try {
            WorkflowRun current = runRepository.findById(run.getId()).orElse(null);
            if (current != null && !TERMINAL_RUN.contains(current.getStatus())) {
                body.run();
            }
        } finally {
            lock.unlock();
            deferredCleanup.stream().filter(id -> lockFor(id) == lock).forEach(this::cleanup);
        }
    }

    private ReentrantLock lockFor(String runId) {
        return runLocks[Math.floorMod(runId.hashCode(), runLocks.length)];
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
            if (abortFailedNode(current, def, latest)) {
                return;
            }
            // An unowned running attempt may already have performed external effects.
            // Without durable fencing, neither replay nor additional work is safe.
            if (latest.values().stream().anyMatch(row -> "running".equals(row.getStatus()))) {
                log.warn("存在无法安全恢复的 running attempt，停止自动重放: run={}", runId);
                return;
            }
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
        runWave(run, def, ready, latest, cancelled, resume, attemptConflict, key -> {}, Map.of());
    }

    private void runWave(WorkflowRun run, WorkflowDefinition def, List<WorkflowNode> ready,
                         Map<String, WorkflowNodeRun> latest, AtomicBoolean cancelled,
                         boolean resume, AtomicBoolean attemptConflict, Consumer<String> submitted,
                         Map<String, RecoveryAttemptRef> refs) {
        // 背压②：单波不超过 max-concurrent-nodes，其余留到下一波（§3.9）
        int limit = Math.max(1, properties.getEngine().getMaxConcurrentNodes());
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        try {
            for (WorkflowNode node : ready.stream().limit(limit).toList()) {
                if (cancelled.get()) {
                    break;
                }
                RecoveryAttemptRef ref = refs.get(node.getKey());
                if (resume && properties.getEngine().isDurableResumeEnabled()
                        && (ref == null || !attemptAllocator.enterRecovery(ref))) {
                    throw new IllegalStateException("recovery enter conflict: " + node.getKey());
                }
                CompletableFuture<Void> future;
                try {
                    future = CompletableFuture.runAsync(
                            () -> runNode(run, def, node, latest, cancelled, resume, attemptConflict, ref), enginePool);
                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                    if (ref != null) attemptAllocator.closeRecoveryAborted(ref, true, ignored -> publish(
                            WorkflowEvent.of("NODE_CANCELLED", run.getId(), node.getKey(), "resume_aborted")));
                    throw rejected;
                }
                futures.add(future);
                submitted.accept(node.getKey());
            }
        } finally {
            for (CompletableFuture<Void> future : futures) {
                try {
                    future.join();
                } catch (CompletionException | java.util.concurrent.CancellationException e) {
                    log.warn("节点执行 future 异常: run={} err={}", run.getId(), e.getMessage());
                }
            }
        }
    }

    private void runNode(WorkflowRun run, WorkflowDefinition def, WorkflowNode node,
                         Map<String, WorkflowNodeRun> latest, AtomicBoolean cancelled,
                          boolean resume, AtomicBoolean attemptConflict, RecoveryAttemptRef ref) {
        String runId = run.getId();
        VariableStore store = variableStores.computeIfAbsent(runId, k -> new VariableStore());

        // ① 取消检查点
        if (cancelled.get()) {
            WorkflowNodeRun existing = latest.get(node.getKey());
            if (existing != null && "running".equals(existing.getStatus())) {
                finishNode(store, run, existing, "cancelled", "user_cancelled", "用户取消", null, ref);
            } else {
                insertStandaloneTerminalRow(runId, node, "cancelled", "user_cancelled", null);
            }
            return;
        }

        // ② 边门控 + 上游失败策略（resume 的节点已过门控）
        if (!resume) {
            GateDecision gate = evaluateGate(def, node, latest, store);
            if (gate.action() == GateAction.SKIP) {
                insertStandaloneTerminalRow(runId, node, "skipped", gate.reason(), "NODE_SKIPPED");
                return;
            }
            if (gate.action() == GateAction.CANCEL) {
                insertStandaloneTerminalRow(runId, node, "cancelled", gate.reason(), "NODE_CANCELLED");
                return;
            }
        }

        // ③ attempt 抢占（uk_node_run 兜底，MF-10）
        WorkflowNodeRun row;
        if (resume) {
            row = latest.get(node.getKey());
            if (ref != null && (row == null || !matchesRecovery(row, ref))) {
                throw new IllegalStateException("recovery attempt changed before execution");
            }
            if (row == null) {
                row = startAttemptSafely(run, node, attemptConflict, latest.get(node.getKey()));
                if (row == null) {
                    return;
                }
            }
        } else {
            row = startAttemptSafely(run, node, attemptConflict, latest.get(node.getKey()));
            if (row == null) {
                return;
            }
        }

        publish(WorkflowEvent.of("NODE_STARTED", runId, node.getKey(), "attempt=" + row.getAttempt()));
        long startedAtMillis = System.currentTimeMillis();

        NodeExecutor executor = registry.executorFor(node.nodeType());
        if (executor == null) {
            finishNode(store, run, row, "failed", "NODE_TYPE_UNKNOWN",
                     "节点类型无执行器: " + node.getType(), null, ref);
            return;
        }

        AtomicBoolean nodeCancelled = new AtomicBoolean(false);
        // 每次调用独立缓冲写入（包括 resume），读取只给拓扑祖先快照。
        // 不开这个视图时，发布期漏掉的引用会在运行期读到兄弟/后继节点输出（横向越权）。
        java.util.Set<String> ancestors = dagValidator.ancestorMap(def)
                .getOrDefault(node.getKey(), java.util.Set.of());
        VariableStore readView = store.createSnapshot(ancestors);
        VariableStore attemptStore = new VariableStore();
        try {
            NodeContext ctx = new NodeContext(run, row, node, def, attemptStore, enginePool, cancelled,
                    nodeCancelled, false, eventSink, readView);
            NodeOutcome outcome = executeWithTimeout(executor, ctx, node, ref);
            switch (outcome) {
                case SUCCEEDED -> finishNode(store, attemptStore, run, row, "succeeded", null, null, null, ref);
                case SKIPPED -> finishNode(store, run, row, "skipped", null, null, null, ref);
                case WAITING -> {
                    if (node.nodeType() == NodeType.APPROVAL
                            || (node.nodeType() == NodeType.SCRIPT_EXEC && properties.getApproval().isDispatchEnabled())) {
                        if (!recognizeApprovalSuspension(ctx)) {
                            finishNode(store, run, row, "cancelled", "cancel_race", "审批未成功挂起", null, ref);
                        } else if (ref != null) {
                            finishNode(store, run, row, "failed", "RECOVERY_WAITING",
                                    "恢复执行再次挂起，禁止自动重派", null, ref);
                        }
                        break;
                    }
                    // 非审批执行器不能通过返回 WAITING 创建或替换审批绑定。
                    finishNode(store, null, run, row, "failed", "WAITING_UNSUPPORTED",
                            "非审批节点不支持挂起", null, true, ref);
                }
                case FAILED -> {
                    if (!DagScheduler.automaticRetryAllowed(node)) {
                        finishNode(store, run, row, "failed", node.nodeType() == NodeType.SCRIPT_EXEC
                                        ? "SCRIPT_RETRY_UNSAFE" : "EXTERNAL_RETRY_UNSAFE",
                                "节点返回失败；外部副作用未知，禁止自动重派", null, ref);
                    } else {
                        finishNode(store, run, row, "failed", "NODE_FAILED", "节点返回失败", null, ref);
                    }
                }
            }
        } catch (NodeCancelledException e) {
            finishNode(store, run, row, "cancelled", "user_cancelled", e.getMessage(), null, ref);
        } catch (NodeFailedException e) {
            handleFailure(run, def, node, row, executor, store, e.getCode(), e.getMessage(), startedAtMillis, ref);
        } catch (ExpressionException e) {
            handleFailure(run, def, node, row, executor, store, e.getCode(), e.getMessage(), startedAtMillis, ref);
        } catch (BusinessException e) {
            // 门禁 449 等：错误信息可能含等级/模式串，但不含脚本原文（C-9）
            handleFailure(run, def, node, row, executor, store, "RISK_BLOCKED", e.getMessage(), startedAtMillis, ref);
        } catch (Exception e) {
            log.error("节点执行未预期异常: run={} node={}", runId, node.getKey(), e);
            handleFailure(run, def, node, row, executor, store, "NODE_INTERNAL",
                    "节点内部错误: " + e.getClass().getSimpleName(), startedAtMillis, ref);
        }
    }

    private WorkflowNodeRun startAttemptSafely(WorkflowRun run, WorkflowNode node, AtomicBoolean attemptConflict,
                                                WorkflowNodeRun previous) {
        try {
            return attemptAllocator.startAttempt(run.getId(), node.getKey(), node.getType(),
                    previous == null ? 0 : previous.getAttempt());
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
                               long startedAtMillis, RecoveryAttemptRef ref) {
        if (ref != null) {
            // A recovery delivery is never retried from an uncertain executor result.
            finishNode(store, run, row, "failed", code, message, null, ref);
            return;
        }
        if (!DagScheduler.automaticRetryAllowed(node)) {
            finishNode(store, run, row, "failed", code,
                    "外部副作用未知，禁止自动重派；" + message, null, ref);
            return;
        }
        int attempt = row.getAttempt() == null ? 1 : row.getAttempt();
        int allowed = isStrictAi(node) ? 1 : node.retryMaxOrDefault(0) + 1;
        boolean retryable = executor.retryable(new NodeFailedException(code, message)) || defaultRetryable(code);
        if (retryable && attempt < allowed) {
            finishNode(store, run, row, "failed", code, message + "（attempt " + attempt + " 失败，将退避重试）", null, ref);
            scheduleRetry(run, node, attempt);
            return;
        }
        finishNode(store, run, row, "failed", code, message, null, ref);
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

    private NodeOutcome executeWithTimeout(NodeExecutor executor, NodeContext ctx, WorkflowNode node,
                                           RecoveryAttemptRef ref) throws Exception {
        int timeoutSec = node.timeoutSecOrDefault(properties.getEngine().getDefaultNodeTimeoutSec());
        CompletableFuture<NodeOutcome> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                if (ref != null && (ctx.nodeCancelled().get() || !attemptAllocator.verifyRecoveryEntered(ref))) {
                    throw new NodeFailedException("RECOVERY_AUTHORITY_LOST", "恢复执行凭据已失效");
                }
                return executor.execute(ctx);
            }, enginePool);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            if (ref != null) {
                attemptAllocator.closeRecoveryAborted(ref, true, ignored -> publish(WorkflowEvent.of(
                        "NODE_CANCELLED", ctx.run().getId(), node.getKey(), "resume_aborted")));
                throw new NodeFailedException("RECOVERY_SUBMISSION_REJECTED", "恢复执行提交被拒绝");
            }
            throw rejected;
        }
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 节点级超时（§3.10 ①）：orphan 线程可能还在跑，翻节点级取消标志让它下一轮退出；
            // 迟到结果由 finishAttemptAtomically 的 status='running' 条件丢弃
            synchronized (ctx.nodeCancelled()) {
                try {
                    if ((node.nodeType() == NodeType.APPROVAL
                            || (node.nodeType() == NodeType.SCRIPT_EXEC && properties.getApproval().isDispatchEnabled()))
                            && recognizeApprovalSuspension(ctx)) {
                        return NodeOutcome.WAITING;
                    }
                } catch (RuntimeException lookupFailure) {
                    ctx.nodeCancelled().set(true);
                    throw lookupFailure;
                }
                ctx.nodeCancelled().set(true);
            }
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

    /** 普通审批 WAITING 只能接受已提交且与本 attempt 对应的绑定。 */
    private boolean recognizeApprovalSuspension(NodeContext ctx) {
        WorkflowNodeRun expected = ctx.nodeRun();
        String expectedType = ctx.node().getType();
        if (!("approval".equals(expectedType) || ("script-exec".equals(expectedType)
                && properties.getApproval().isDispatchEnabled()))) return false;
        return nodeRunRepository.findById(expected.getId()).filter(actual ->
                "waiting".equals(actual.getStatus()) && expectedType.equals(actual.getNodeType())
                && Objects.equals(actual.getRunId(), ctx.run().getId())
                && Objects.equals(actual.getNodeKey(), ctx.node().getKey())
                && Objects.equals(actual.getAttempt(), expected.getAttempt())
                && actual.getApprovalId() != null
                && approvalRepository.findById(actual.getApprovalId()).filter(approval ->
                        Objects.equals(approval.getRunId(), actual.getRunId())
                        && Objects.equals(approval.getNodeKey(), actual.getNodeKey())
                        && expectedType.equals(approval.getNodeType())).isPresent()
                && (!"script-exec".equals(expectedType) || validDispatchSuspension(actual)))
                .map(actual -> { expected.setApprovalId(actual.getApprovalId()); return true; }).orElse(false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.easyshell.server.repository.WorkflowDispatchApprovalBindingRepository dispatchBindings;

    private boolean validDispatchSuspension(WorkflowNodeRun row) {
        if (dispatchBindings == null || row.getTaskId() != null) return false;
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> dispatchBindings.find(row.getApprovalId())
                .filter(binding -> row.getId().equals(binding.nodeRunId())
                        && Objects.equals(row.getAttempt(), binding.attempt())
                        && binding.userId() != null && binding.userId() > 0 && binding.timeoutSec() != null
                        && binding.timeoutSec() > 0 && binding.targets() != null && !binding.targets().isBlank()
                        && binding.consumedAt() == null && binding.taskId() == null).isPresent()));
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
            // skipped 表示路径未被选中，不是成功的空输出；必须跨多跳传播。
            if ("skipped".equals(status)) {
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
                // 分支节点失败时，即使旧 attempt 留有输出也不能据此选择分支。
                WorkflowNode source = def.nodeByKey(edge.getFrom());
                if (source != null && (source.nodeType() == NodeType.CONDITION
                        || source.nodeType() == NodeType.CLASSIFIER)) {
                    continue;
                }
                // PARTIAL：普通上游允许继续，保留现有失败策略。
            }
            Boolean handleDecision = evaluateConditionHandle(def, edge, store);
            if (handleDecision != null) {
                // condition 出边的 sourceHandle 门控优先于 edge.condition（handle 存在即取代）
                if (!handleDecision) {
                    continue;
                }
            } else if (edge.getCondition() != null && !edge.getCondition().isBlank()) {
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

    /**
     * condition / classifier 出边 handle 门控（工作流 2.0 §2.2 + 2.1 §3）。
     *
     * @return null = 本边不适用 handle 门控（上游不是 condition/classifier，或旧 condition 边使用表达式），
     *         交由旧 edge.condition 语义处理；TRUE/FALSE = handle 的通行判定。
     *         <b>fail-closed</b>：上游输出缺失/result 不是布尔值/handle 非法时返回 false。
     */
    private Boolean evaluateConditionHandle(WorkflowDefinition def, WorkflowEdge edge,
                                            VariableStore store) {
        String handle = edge.getSourceHandle();
        WorkflowNode upstream = def.nodeByKey(edge.getFrom());
        // 旧定义以 edge.condition 选择 condition 分支；仍需实际求值，不能直接放行。
        if (upstream != null && upstream.nodeType() == NodeType.CONDITION
                && (handle == null || handle.isBlank())
                && edge.getCondition() != null && !edge.getCondition().isBlank()) {
            // 仅接受旧版标准分支表达式，不能用恒真或复合表达式绕过分支门控。
            String resultPath = "nodes." + edge.getFrom() + ".output.result";
            String legacy = edge.getCondition().trim();
            if (legacy.matches(java.util.regex.Pattern.quote(resultPath) + "\\s*==\\s*(true|false)")) {
                return null;
            }
            return false;
        }
        if (upstream != null && (upstream.nodeType() == NodeType.CLASSIFIER
                || upstream.nodeType() == NodeType.CONDITION) && (handle == null || handle.isBlank())) {
            store.addWarning("分支出边缺少 sourceHandle，边未通行: " + edge.getFrom() + " -> " + edge.getTo());
            return false;
        }
        if (upstream != null && upstream.nodeType() == NodeType.CLASSIFIER) {
            return evaluateClassifierHandle(upstream, edge, handle, store);
        }
        if (upstream == null || upstream.nodeType() != NodeType.CONDITION) {
            return null;   // 非 condition 的 sourceHandle（如 out）不影响门控
        }
        Boolean result = readConditionResult(store, edge.getFrom());
        boolean isTrueHandle = "true".equals(handle);
        boolean isFalseHandle = "false".equals(handle);
        if (result == null || (!isTrueHandle && !isFalseHandle)) {
            store.addWarning("condition 出边门控失败（sourceHandle=" + handle + "，上游输出"
                    + (result == null ? "缺失或 result 不是布尔值" : "可解析")
                    + "），边未通行: " + edge.getFrom() + " -> " + edge.getTo());
            return false;
        }
        return isTrueHandle ? result : !result;
    }

    /**
     * classifier 动态 handle 门控（工作流 2.1 §3）。
     *
     * <ul>
     *   <li>类别 handle：{@code output.class} 与 handle 精确相等才通行；</li>
     *   <li>{@code default}：无有效类别时也不能通行；</li>
     *   <li>分类器输出整体缺失 / 不可读：<b>所有</b> handle 一律不通行（fail-closed）；</li>
     *   <li>handle 不在配置类别中：不通行（发布期已报 ERROR，这里是运行期兜底）。</li>
     * </ul>
     */
    private Boolean evaluateClassifierHandle(WorkflowNode upstream, WorkflowEdge edge,
                                             String handle, VariableStore store) {
        VariableStore.StoredOutput output = store.nodeOutput(edge.getFrom());
        if (output == null) {
            store.addWarning("classifier 出边门控失败（上游输出缺失），边未通行: "
                    + edge.getFrom() + " -> " + edge.getTo());
            return false;
        }

        Set<String> classes = configuredClassifierClasses(upstream);
        Object classValue = readClassifierClass(output);
        if (!(classValue instanceof String text) || !classes.contains(text) || "default".equals(text)) {
            store.addWarning("classifier 出边门控失败（类别缺失、未知或不可读），边未通行: "
                    + edge.getFrom() + " -> " + edge.getTo());
            return false;
        }

        if ("default".equals(handle)) {
            return false;
        }
        if (!classes.contains(handle)) {
            store.addWarning("classifier 出边 handle '" + handle + "' 不在配置类别中，边未通行: "
                    + edge.getFrom() + " -> " + edge.getTo());
            return false;
        }
        return handle.equals(classValue);
    }

    /** 哨兵：输出存在但结构不可读。 */
    private static final Object READ_UNREADABLE = new Object();

    private Object readClassifierClass(VariableStore.StoredOutput output) {
        Object value = output.value();
        if (value instanceof Map<?, ?> map) {
            return map.containsKey("class") ? map.get("class") : null;
        }
        Object parsed = ExpressionEvaluator.readJsonPath(output.json(), "class");
        if (parsed != null) {
            return parsed;
        }
        // 非 Map 且 JSON 里也读不到 class：可能是合法 JSON 但没有 class（null），
        // 也可能是 preview/非法 JSON。后者不可读 → fail-closed。
        String json = output.json();
        if (json != null && (json.trim().startsWith("{") || json.trim().startsWith("["))) {
            Object root = ExpressionEvaluator.readJsonPath(json, "$");
            if (root instanceof Map<?, ?>) {
                return null;
            }
        }
        return READ_UNREADABLE;
    }

    private Set<String> configuredClassifierClasses(WorkflowNode node) {
        Set<String> names = new java.util.LinkedHashSet<>();
        java.util.List<Object> rows = node.configList("classes");
        if (rows == null) {
            return names;
        }
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map && map.get("name") instanceof String name
                    && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /** 读取 condition 节点输出里的 result（只认 Boolean 与严格 "true"/"false" 字符串）。 */
    private Boolean readConditionResult(VariableStore store, String nodeKey) {
        VariableStore.StoredOutput output = store.nodeOutput(nodeKey);
        if (output == null) {
            return null;
        }
        Object value = output.value();
        if (value instanceof Map<?, ?> map) {
            return asBoolean(map.get("result"));
        }
        if (value == null) {
            return null;
        }
        return asBoolean(ExpressionEvaluator.readJsonPath(output.json(), "result"));
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String text = s.trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return null;
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
            afterCommit(() -> cleanup(runId));
            return false;
        }
        if (Boolean.TRUE.equals(current.getCancelRequested())) {
            cancelRun(current, "cancelled", "user_cancelled");
            return false;
        }
        List<WorkflowNodeRun> rows = nodeRunRepository.findByRunId(runId);
        Map<String, WorkflowNodeRun> latest = DagScheduler.latestByKey(rows);

        if (abortFailedNode(current, def, latest)) {
            return false;
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
        if (properties.getEngine().isDurableResumeEnabled()
                && rows.stream().anyMatch(row -> "running".equals(row.getStatus()))) {
            // Known aborts above retain priority; unknown work never opens another wave.
            return false;
        }
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

    /** 波末与恢复调度前使用同一失败判定；严格 AI 不受 partial 或重试影响。 */
    private boolean abortFailedNode(WorkflowRun run, WorkflowDefinition def,
                                          Map<String, WorkflowNodeRun> latest) {
        for (WorkflowNode node : def.getNodes()) {
            WorkflowNodeRun row = latest.get(node.getKey());
            if (row != null && NODE_FAILED.contains(row.getStatus())
                    && (isStrictAi(node) || (!DagScheduler.hasRetryRemaining(node, row)
                    && FailurePolicyResolver.ownFailure(node, def) == FailurePolicy.ABORT))) {
                cancelRun(run, "failed", "upstream_failed", "node_failed:" + node.getKey());
                return true;
            }
        }
        return false;
    }

    private boolean isStrictAi(WorkflowNode node) {
        return (node.nodeType() == NodeType.LLM || node.nodeType() == NodeType.AI_ANALYZE)
                && "json_schema_v1".equals(node.configString("outputMode"));
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
        terminalTransaction(() -> {
            finalizeRunInTransaction(run);
        });
    }

    private void finalizeRunInTransaction(WorkflowRun run) {
        String runId = run.getId();
        WorkflowRun current = runRepository.lockById(runId).orElse(null);
        if (current == null || TERMINAL_RUN.contains(current.getStatus())) {
            afterCommit(() -> cleanup(runId));
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
                buildRunOutput(runId, def, latest), truncate(error, 1000), TERMINAL_RUN);
        afterCommit(() -> {
            try {
                if (finished == 1) {
                    publish(WorkflowEvent.of(anyFailed ? "RUN_FAILED" : "RUN_SUCCEEDED", runId, null,
                            anyFailed ? truncate(error, 200) : "运行完成"));
                }
            } finally {
                cleanup(runId);
            }
        });
    }

    /**
     * 运行输出契约（工作流 2.0 §2.4）：
     * 定义里恰好一个 succeeded 的 end 节点时，优先用 {@link VariableStore#runOutput()}
     * 专用通道（完整对象，超过 8KB 也不会被 preview 破坏）；其次用有效的 output_json。
     * 否则沿用旧的"各节点 output_json 聚合"。end 未执行/失败/输出无法解析、
     * 定义缺失、或有多个 end（校验器会拦，但旧数据防御）时都安全回退到聚合。
     */
    private String buildRunOutput(String runId, WorkflowDefinition def,
                                  Map<String, WorkflowNodeRun> latest) {
        if (def != null && def.getNodes() != null) {
            List<WorkflowNode> ends = def.getNodes().stream()
                    .filter(n -> n.nodeType() == NodeType.END)
                    .toList();
            if (ends.size() == 1) {
                WorkflowNodeRun endRow = latest.get(ends.get(0).getKey());
                if (endRow != null && "succeeded".equals(endRow.getStatus())) {
                    VariableStore store = variableStores.get(runId);
                    Object dedicated = store == null ? null : store.runOutput();
                    if (dedicated != null) {
                        return toJson(dedicated);
                    }
                    if (isValidJson(endRow.getOutputJson())) {
                        return endRow.getOutputJson();
                    }
                }
            }
        }
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

    /** output_json 是否是有效 JSON（End 行被 preview 截断时不能当整体输出） */
    private boolean isValidJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(json) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void failRun(WorkflowRun run, String reason) {
        String safeReason = sensitiveDataFilter.filter(reason);
        transactionTemplate.execute(status -> {
            long duration = run.getStartedAt() == null ? 0L
                    : Duration.between(run.getStartedAt(), LocalDateTime.now()).toMillis();
            int finished = runRepository.finishRunAtomically(run.getId(), "failed", LocalDateTime.now(), duration,
                    null, truncate(safeReason, 1000), TERMINAL_RUN);
            afterCommit(() -> {
                try {
                    if (finished == 1) {
                        publish(WorkflowEvent.of("RUN_FAILED", run.getId(), null, safeReason));
                    }
                } finally {
                    cleanup(run.getId());
                }
            });
            return null;
        });
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
        terminalTransaction(() -> {
            int marked = runRepository.requestCancelAtomically(runId);
            if (marked == 0) {
                return;   // 已取消/已终态/不存在：幂等返回
            }
            WorkflowRun run = runRepository.findById(runId).orElse(null);
            if (run == null) {
                return;
            }
            List<String> taskIds = nodeRunRepository.findByRunId(runId).stream()
                    .map(WorkflowNodeRun::getTaskId).filter(Objects::nonNull).distinct().toList();
            // 取消必须留痕：操作人 + runId + 被取消时各节点的 task_id 清单（§3.10 ⑤）
            auditLogService.logSync(userId, "SYSTEM", "WORKFLOW_RUN_CANCEL", "workflow_run", runId,
                    "task_ids=" + taskIds, null, "success");
            cancelRunInTransaction(run, "cancelled", "user_cancelled", "user_cancelled");
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
        terminalTransaction(() -> {
            cancelRunInTransaction(run, runStatus, nodeReason, runError);
        });
    }

    private void cancelRunInTransaction(WorkflowRun run, String runStatus, String nodeReason, String runError) {
        String runId = run.getId();
        String safeRunError = sensitiveDataFilter.filter(runError);
        String safeNodeReason = sensitiveDataFilter.filter(nodeReason);
        WorkflowRun current = runRepository.lockById(runId).orElse(null);
        if (current == null) {
            afterCommit(() -> cleanup(runId));
            return;
        }
        if (TERMINAL_RUN.contains(current.getStatus())) {
            afterCommit(() -> cleanup(runId));
            return;
        }
        long duration = current.getStartedAt() == null ? 0L
                : Duration.between(current.getStartedAt(), LocalDateTime.now()).toMillis();
        int finished = runRepository.finishRunAtomically(runId, runStatus, LocalDateTime.now(), duration,
                null, truncate(safeRunError, 1000), TERMINAL_RUN);
        if (finished == 0) {
            afterCommit(() -> cleanup(runId));
            return;   // 已有其他线程写过终态（取消 vs 恢复的竞态由此确定收敛）
        }

        // ① 未开始与等待中的节点批量取消
        nodeRunRepository.cancelUnfinishedNodes(runId, safeNodeReason);

        // ② 从未开始的节点补一行 cancelled 留痕（运行详情页能看到"为什么没执行"）
        WorkflowDefinition def = loadDefinition(current.getVersionId());
        if (def != null) {
            Map<String, WorkflowNodeRun> latest = latestByKey(runId);
            for (WorkflowNode node : def.getNodes()) {
                if (!latest.containsKey(node.getKey())) {
                    insertTerminalRow(runId, node, "cancelled", safeNodeReason);
                }
            }
        }

        // ③ 已派发 job 逻辑取消：结果回传被 job 终态检查丢弃
        cancelDispatchedJobs(runId);

        // ④ 悬死审批清理（SF-7）
        approvalRepository.cancelPendingByRunId(runId);

        afterCommit(() -> {
            AtomicBoolean flag = cancelFlags.get(runId);
            if (flag != null) {
                flag.set(true);
            }
            try {
                publish(WorkflowEvent.of("cancelled".equals(runStatus) ? "RUN_CANCELLED" : "RUN_FAILED",
                        runId, null, safeRunError));
            } finally {
                cleanup(runId);
            }
        });
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
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
        }).stream().filter(node -> def.inEdges(node.getKey()).stream().noneMatch(edge -> {
            WorkflowNode source = def.nodeByKey(edge.getFrom());
            WorkflowNodeRun row = latest.get(edge.getFrom());
            if (source == null || row == null || !("failed".equals(row.getStatus())
                    || "timeout".equals(row.getStatus()))) {
                return false;
            }
            return DagScheduler.hasRetryRemaining(source, row);
        })).toList();
    }

    /** finishNode：attempt 终态写入 + 变量状态刷新 + 事件 */
    private void finishNode(VariableStore store, WorkflowRun run, WorkflowNodeRun row, String status,
                            String errorCode, String errorMessage, String warningMessage) {
        finishNode(store, run, row, status, errorCode, errorMessage, warningMessage, null);
    }

    private void finishNode(VariableStore store, WorkflowRun run, WorkflowNodeRun row, String status,
                            String errorCode, String errorMessage, String warningMessage, RecoveryAttemptRef ref) {
        finishNode(store, null, run, row, status, errorCode, errorMessage, warningMessage, ref);
    }

    private void finishNode(VariableStore store, VariableStore attemptStore, WorkflowRun run, WorkflowNodeRun row,
                            String status, String errorCode, String errorMessage, String warningMessage,
                            RecoveryAttemptRef ref) {
        finishNode(store, attemptStore, run, row, status, errorCode, errorMessage, warningMessage, false, ref);
    }

    private void finishNode(VariableStore store, VariableStore attemptStore, WorkflowRun run, WorkflowNodeRun row,
                            String status, String errorCode, String errorMessage, String warningMessage,
                            boolean preserveApprovalBinding, RecoveryAttemptRef ref) {
        if (ref != null && "RECOVERY_SUBMISSION_REJECTED".equals(errorCode)) return;
        State recoveryState = null;
        if (ref != null) {
            recoveryState = errorCode == null && ("succeeded".equals(status) || "skipped".equals(status))
                    ? State.SUCCEEDED : State.UNCERTAIN;
        }
        VariableStore.StoredOutput output = attemptStore == null ? null : attemptStore.nodeOutput(row.getNodeKey());
        List<String> warnings = attemptStore == null ? List.of() : attemptStore.drainWarnings();
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
        if (ref != null && "approval".equals(row.getNodeType()) && "approval_cancelled".equals(errorCode)) {
            effectiveStatus = "cancelled";
        }
        if (("succeeded".equals(status) || "skipped".equals(status))
                && runRepository.findById(run.getId())
                        .map(current -> TERMINAL_RUN.contains(current.getStatus())
                                || Boolean.TRUE.equals(current.getCancelRequested())).orElse(true)) {
            effectiveStatus = "cancelled";
            error = "run 已终态，节点结果作废";
        }
        if (!"succeeded".equals(effectiveStatus)) {
            output = null;
            warning = warningMessage;
        }
        String filteredError = truncate(sensitiveDataFilter.filter(error), 1000);
        int updated = attemptAllocator.finishAttempt(row, AttemptAllocator.AttemptResult.builder()
                .status(effectiveStatus)
                // MF-13：执行体回填的渲染后 parameters Map 必须随终态一起落库（P-1）。
                // startAttempt 的新行里没有它，只有这条 UPDATE 能把它写进 input_json。
                .inputJson(row.getInputJson())
                .outputJson(output == null ? null : truncate(output.json(), 262144))
                .outputSha256(output == null ? null : output.sha256())
                .previewSha256(output == null ? null : output.previewSha256())
                .rawParams(row.getRawParams())
                .artifactIds(row.getArtifactIds())
                // 错误信息可能拼入表达式/上游值，落库前统一脱敏（出站展示还会再过滤一次）
                .errorMessage(filteredError)
                .warningMessage(truncate(sensitiveDataFilter.filter(warning), 1000))
                .agentVersions(row.getAgentVersions())
                .taskId(row.getTaskId())
                .approvalId(row.getApprovalId())
                .cancelDelivered(false)
                .durationMs(duration)
                .build(), outcome -> publishStaleNode(row.getRunId(), row.getNodeKey(), outcome),
                preserveApprovalBinding, ref, recoveryState, errorCode);
        if (updated == 0) {
            log.info("迟到结果被丢弃（attempt 已终态或被并发收尾）: run={} node={} attempt={}",
                    run.getId(), row.getNodeKey(), row.getAttempt());
            return;
        }
        if ("succeeded".equals(effectiveStatus) && attemptStore != null) {
            store.mergeAttempt(attemptStore, row.getNodeKey());
        }
        store.putNodeStatus(row.getNodeKey(), effectiveStatus, duration, row.getAttempt());
        publish(WorkflowEvent.of(switch (effectiveStatus) {
            case "succeeded" -> "NODE_SUCCEEDED";
            case "skipped" -> "NODE_SKIPPED";
            case "cancelled" -> "NODE_CANCELLED";
            default -> "NODE_FAILED";
        }, run.getId(), row.getNodeKey(), effectiveStatus.equals("succeeded") ? null : filteredError));
    }

    void publishStaleNode(String runId, String nodeKey, String outcome) {
        publish(WorkflowEvent.of("cancelled".equals(outcome) ? "NODE_CANCELLED" : "NODE_FAILED",
                runId, nodeKey, null));
    }

    /** 整个事务回滚后重新读取；参与外层事务时不能安全重试。 */
    private void terminalTransaction(Runnable action) {
        boolean participating = TransactionSynchronizationManager.isActualTransactionActive();
        for (int attempt = 0; ; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(tx -> action.run());
                return;
            } catch (TerminalRowConflict e) {
                if (participating || attempt >= 2) {
                    throw e;
                }
                log.debug("终态留痕冲突，回滚整个事务后重试: attempt={}", attempt + 1);
            }
        }
    }

    private void insertStandaloneTerminalRow(String runId, WorkflowNode node, String status, String reason,
                                             String eventType) {
        terminalTransaction(() -> {
            if (runRepository.lockById(runId).isPresent() && insertTerminalRow(runId, node, status, reason)
                    && eventType != null) {
                afterCommit(() -> publish(WorkflowEvent.of(eventType, runId, node.getKey(), reason)));
            }
        });
    }

    /** 留痕与终态在同一事务中提交，冲突只在事务边界处理。 */
    private boolean insertTerminalRow(String runId, WorkflowNode node, String status, String reason) {
        // ① 已有 attempt 行（引擎侧同一瞬间抢占同一 attempt / 上一次留痕已落库）：不写第二条
        if (nodeRunRepository.findByRunIdAndNodeKeyAndAttempt(runId, node.getKey(), 1).isPresent()) {
            log.debug("终态留痕跳过（已有 attempt=1 行）: run={} node={} status={}", runId, node.getKey(), status);
            return false;
        }
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
            nodeRunRepository.saveAndFlush(row);
            return true;
        } catch (DataIntegrityViolationException e) {
            if (isNodeRunUniqueConflict(e)) {
                throw new TerminalRowConflict(e);
            }
            throw e;
        }
    }

    private static boolean isNodeRunUniqueConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql &&
                    ("23505".equals(sql.getSQLState()) ||
                            ("23000".equals(sql.getSQLState()) && sql.getErrorCode() == 1062))) {
                String message = sql.getMessage();
                if (message != null && java.util.regex.Pattern.compile(
                        "(?i)(?<![a-z0-9_])uk_node_run(?:_index_[a-z0-9]+)?(?![a-z0-9_])")
                        .matcher(message).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class TerminalRowConflict extends RuntimeException {
        private TerminalRowConflict(Throwable cause) {
            super(cause);
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
        ReentrantLock lock = lockFor(runId);
        if (!lock.tryLock()) {
            deferredCleanup.add(runId);
            // Close the race with the execution thread releasing its lock.
            if (!lock.tryLock()) {
                return;
            }
        }
        try {
            deferredCleanup.remove(runId);
            cancelFlags.remove(runId);
            variableStores.remove(runId);
            retryDue.entrySet().removeIf(e -> e.getKey().startsWith(runId + ":"));
            pendingWakeups.remove(runId);
        } finally {
            lock.unlock();
        }
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
        Map<String, Object> inputCopy = input == null ? Map.of() : new LinkedHashMap<>(input);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("variable initialization requires transaction synchronization");
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    initializeCommittedVariables(run, def, inputCopy);
                }
            });
        } else {
            initializeCommittedVariables(run, def, inputCopy);
        }
    }

    private void seedVariables(VariableStore store, WorkflowRun run, Map<String, Object> input) {
        store.putRunMetadata("run.id", run.getId());
        String workflowName = run.getWorkflowId() == null ? null
                : workflowRepository.findById(run.getWorkflowId())
                        .map(com.easyshell.server.model.entity.Workflow::getName).orElse(null);
        store.putRunMetadata("run.workflowName",
                workflowName != null ? workflowName : run.getWorkflowId());
        store.putRunMetadata("run.startedBy", run.getStartedBy());
        store.putRunMetadata("run.triggerType", run.getTriggerType());
        if (input != null) {
            input.forEach((key, value) -> store.putInput(key, value));
        }
    }

    /**
     * 引擎入口兜底播种：仅当该 run 尚未在内存中建立变量存储时，从 run 行
     * （{@code input_json} + 版本定义）重建 {@code run.input.*} 与 run 元信息。
     *
     * <p>为什么必须放在引擎而不是只放在 {@code WorkflowRunService}：queued run 由
     * {@code WorkflowPendingWakeupScanner} 泵回、心跳过期 run 由扫描器重驱动，这两条路径
     * 都不经过立即启动分支。没有这次兜底，它们会在"入参不存在"的状态下执行——
     * condition/transform/ai 都会 fail-closed 跳过，表现为"从队列启动的 run 永远走 false"。</p>
     */
    public void initVariablesIfAbsent(WorkflowRun run) {
        if (run == null || run.getId() == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("variable recovery cannot publish inside a transaction");
        }
        initializeCommittedVariables(run, null, null);
    }

    private void initializeCommittedVariables(WorkflowRun run, WorkflowDefinition suppliedDefinition,
                                              Map<String, Object> suppliedInput) {
        ReentrantLock lock = lockFor(run.getId());
        lock.lock();
        try {
            VariableStore handle = variableStore(run.getId());
            if (handle.isInitialized()) {
                return;
            }
            WorkflowDefinition def = suppliedDefinition != null ? suppliedDefinition : loadDefinition(run.getVersionId());
            if (def == null) {
                return;
            }
            VariableStore candidate = new VariableStore();
            seedVariables(candidate, run, suppliedInput != null ? suppliedInput : parseRunInput(run.getInputJson()));
            if (def.getNodes() != null) {
                restoreSucceededNodeOutputs(run.getId(), def, candidate);
            }
            handle.publishInitialized(candidate);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重驱动前从 {@code workflow_node_run} 恢复已成功节点的输出。
     *
     * <p>重启/队列泵回时内存 store 为空；只恢复 run.input 会让已完成节点变成"输出不存在"，
     * 下游 condition 会静默走 false 分支。succeeded 行按 output_json 反序列化回
     * {@link VariableStore#putNodeOutput}（含 outputVar 别名）。condition 输出无法恢复时
     * 无法证明输出完整时直接失败（VARIABLE_RESTORE_FAILED），不执行下游。</p>
     */
    private boolean initVariablesIfAbsentOrFail(WorkflowRun run) {
        if (run != null && TERMINAL_RUN.contains(run.getStatus())) {
            return true;   // 已终态：driveLocked 会自行清理，不因恢复失败伪造 RUN_FAILED
        }
        try {
            initVariablesIfAbsent(run);
            return true;
        } catch (NodeFailedException e) {
            // 恢复失败必须显式终止：静默继续会让 condition 两条分支都不走
            failRun(run, e.getCode() + ": " + e.getMessage());
            return false;
        }
    }

    private void restoreSucceededNodeOutputs(String runId, WorkflowDefinition def, VariableStore store) {
        Map<String, WorkflowNodeRun> latest = latestByKey(runId);
        for (WorkflowNode node : def.getNodes()) {
            WorkflowNodeRun row = latest.get(node.getKey());
            if (row == null || !"succeeded".equals(row.getStatus())) {
                continue;
            }
            String json = row.getOutputJson();
            if (json == null || row.getOutputSha256() == null || row.getPreviewSha256() != null
                    || !row.getOutputSha256().equals(sha256Output(json))) {
                throw restoreFailure(node);
            }
            Object parsed;
            try {
                parsed = objectMapper.readerFor(Object.class)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
                // putNodeOutput serializes again with its own default mapper. Reject values
                // that would change on that round trip rather than invent a new fingerprint.
                if (parsed == null || !json.equals(new ObjectMapper().writeValueAsString(parsed))) {
                    throw restoreFailure(node);
                }
            } catch (NodeFailedException e) {
                throw e;
            } catch (Exception e) {
                throw restoreFailure(node);
            }
            if (node.nodeType() == NodeType.CONDITION) {
                Object result = parsed instanceof Map<?, ?> map ? map.get("result") : null;
                if (!(result instanceof Boolean)) {
                    throw new NodeFailedException("VARIABLE_RESTORE_FAILED",
                            "条件节点 " + node.getKey() + " 的已成功输出无法恢复（result 缺失或非布尔值）");
                }
            }
            VariableStore.StoredOutput restored = store.putNodeOutput(node.getKey(), node.getOutputVar(), parsed);
            if (restored.truncated() || !row.getOutputSha256().equals(restored.sha256())) {
                throw restoreFailure(node);
            }
            store.putNodeStatus(node.getKey(), row.getStatus(), row.getDurationMs(), row.getAttempt());
        }
    }

    private NodeFailedException restoreFailure(WorkflowNode node) {
        return new NodeFailedException("VARIABLE_RESTORE_FAILED",
                "节点 " + node.getKey() + " 的已成功输出无法证明完整并恢复");
    }

    private String sha256Output(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Map<String, Object> parseRunInput(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            // 不打印异常 message：Jackson 的解析错误可能带输入片段
            log.warn("run.input_json 解析失败（按空入参处理）");
            return Map.of();
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
