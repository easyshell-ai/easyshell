package com.easyshell.server.workflow.engine;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.repository.WorkflowVersionRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.approval.WorkflowApprovalStore;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作流看门狗（§3.10 四级超时 + 僵尸 attempt 兜底）。
 *
 * <p>扫描项：① run 级超时（{@code started_at + timeout < now}，默认 3600s）；
 * ② 审批超时（{@code expires_at} 到点，按 onTimeout 语义由 resume 收尾）；
 * ③ 节点 attempt 超时（兜底置 timeout 再驱动，因未知副作用默认关闭）。
 * ④ Task 级超时零成本继承 {@code TaskServiceImpl.timeoutStaleJobs()}——本类不重复实现。</p>
 */
@Slf4j
@Component
public class WorkflowWatchdog {

    private static final List<String> ACTIVE_RUN = List.of("pending", "queued", "running");

    private final WorkflowRunRepository runRepository;
    private final WorkflowNodeRunRepository nodeRunRepository;
    private final WorkflowApprovalRepository approvalRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowApprovalStore approvalStore;
    private final WorkflowEngine engine;
    private final AuditLogService auditLogService;
    private final WorkflowProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 显式事务边界：看门狗跑在 {@code workflow-scheduler-} 线程上，没有线程绑定的事务，
     * 而僵尸 attempt 的收尾是一条 {@code @Modifying} UPDATE——没有事务会被 Hibernate 拒绝。
     */
    private final TransactionTemplate transactionTemplate;

    public WorkflowWatchdog(WorkflowRunRepository runRepository,
                            WorkflowNodeRunRepository nodeRunRepository,
                            WorkflowApprovalRepository approvalRepository,
                            WorkflowVersionRepository versionRepository,
                            WorkflowApprovalStore approvalStore,
                            WorkflowEngine engine,
                            AuditLogService auditLogService,
                            WorkflowProperties properties,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.approvalRepository = approvalRepository;
        this.versionRepository = versionRepository;
        this.approvalStore = approvalStore;
        this.engine = engine;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${easyshell.workflow.engine.watchdog-interval-sec:30}000",
            initialDelay = 20000)
    public void scan() {
        try {
            timeoutRuns();
            expireDispatchApprovals();
            if (properties.getEngine().isAutomaticRecoveryEnabled()) {
                expireApprovals();
                if (!properties.getEngine().isDurableResumeEnabled()) {
                    timeoutStaleAttempts();
                }
            }
        } catch (Exception e) {
            log.error("看门狗扫描异常: {}", e.getMessage(), e);
        }
    }

    /** Known unexecuted approval waits can expire without enabling recovery of unknown work. */
    private void expireDispatchApprovals() {
        if (!properties.getApproval().isDispatchEnabled()) return;
        for (WorkflowApproval approval : approvalRepository.findByStatusAndExpiresAtBefore("pending", LocalDateTime.now())) {
            if (!"script-exec".equals(approval.getNodeType())) continue;
            transactionTemplate.executeWithoutResult(tx -> {
                WorkflowRun run = runRepository.lockById(approval.getRunId()).orElse(null);
                if (run == null || !List.of("running", "waiting").contains(run.getStatus())) return;
                if (approvalStore.expire(approval.getId())) {
                    engine.cancelRun(run, "timeout", "dispatch_approval_timeout", "派发审批已过期，命令未执行");
                }
            });
        }
    }

    /** ① run 级超时（waiting 的 run 由审批 expires_at 兜底，不在这里杀） */
    private void timeoutRuns() {
        LocalDateTime deadline = LocalDateTime.now()
                .minusSeconds(properties.getEngine().getDefaultRunTimeoutSec());
        for (WorkflowRun run : runRepository.findByStatusInAndStartedAtBefore(ACTIVE_RUN, deadline)) {
            log.warn("run 级超时: run={} startedAt={}", run.getId(), run.getStartedAt());
            engine.cancelRun(run, "timeout", "run_timeout");
            auditLogService.logSync(run.getStartedBy(), "SYSTEM", "WORKFLOW_RUN_TIMEOUT", "workflow_run",
                    run.getId(), "run 级超时，已置 timeout", null, "warning");
        }
    }

    /** ② 审批超时：expires_at 到点置 timeout，然后唤醒 run 让节点按 onTimeout 收尾 */
    private void expireApprovals() {
        LocalDateTime now = LocalDateTime.now();
        for (WorkflowApproval approval : approvalRepository.findByStatusAndExpiresAtBefore("pending", now)) {
            if (approvalStore.expire(approval.getId())) {
                log.warn("审批超时: approval={} run={} node={}", approval.getId(), approval.getRunId(),
                        approval.getNodeKey());
                if (properties.getEngine().isDurableResumeEnabled()) {
                    engine.submitResume(approval.getRunId());
                } else {
                    engine.resumeRun(approval.getRunId());
                }
            }
        }
    }

    /**
     * ③ 僵尸 attempt 兜底：running 的 attempt 行超过节点 timeoutSec 仍没终态，
     * 说明执行线程已经死了（线程池被打满/异常吞掉）。置 timeout 后再驱动一次，
     * 让下游按失败策略收敛——否则这些行会永远挡住后续调度波。
     */
    private void timeoutStaleAttempts() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, List<WorkflowNodeRun>> byRun = new java.util.LinkedHashMap<>();
        for (WorkflowNodeRun row : nodeRunRepository.findByStatus("running")) {
            byRun.computeIfAbsent(row.getRunId(), k -> new java.util.ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<WorkflowNodeRun>> entry : byRun.entrySet()) {
            String runId = entry.getKey();
            Optional<WorkflowRun> runOpt = runRepository.findById(runId);
            if (runOpt.isEmpty()) {
                continue;
            }
            WorkflowDefinition def = loadDefinition(runOpt.get().getVersionId());
            if (def == null) {
                continue;
            }
            boolean anyTimedOut = false;
            for (WorkflowNodeRun row : entry.getValue()) {
                WorkflowNode node = def.nodeByKey(row.getNodeKey());
                if (node == null || row.getStartedAt() == null) {
                    continue;
                }
                int timeoutSec = node.timeoutSecOrDefault(properties.getEngine().getDefaultNodeTimeoutSec());
                if (row.getStartedAt().plusSeconds(timeoutSec).isAfter(now)) {
                    continue;
                }
                String rowId = row.getId();
                long durationMs = Duration.between(row.getStartedAt(), now).toMillis();
                Integer marked = transactionTemplate.execute(tx -> {
                    WorkflowRun locked = runRepository.lockById(runId).orElse(null);
                    if (locked == null) return 0;
                    boolean active = "running".equals(locked.getStatus())
                            && Boolean.FALSE.equals(locked.getCancelRequested());
                    String outcome = active || "timeout".equals(locked.getStatus()) ? "timeout" : "cancelled";
                    int updated = nodeRunRepository.closeStaleAttempt(rowId, outcome,
                                null, null,
                                active ? "attempt 超过 " + timeoutSec + "s 未终态（看门狗兜底）"
                                        : "run 已结束或取消；外部执行可能仍在运行",
                                 now, durationMs);
                    if (updated == 1) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                engine.publishStaleNode(runId, row.getNodeKey(), outcome);
                            }
                        });
                    }
                    return updated == 1 && active ? 2 : updated;
                });
                if (marked != null && marked > 0) {
                    anyTimedOut |= marked == 2;
                    log.warn("僵尸 attempt 已收尾: run={} node={} attempt={}",
                            runId, row.getNodeKey(), row.getAttempt());
                }
            }
            if (anyTimedOut) {
                engine.submitDrive(runOpt.get());
            }
        }
    }

    private WorkflowDefinition loadDefinition(Long versionId) {
        return versionRepository.findDefinition(versionId)
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, WorkflowDefinition.class);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    /** 供测试：手动触发一次扫描 */
    public void scanOnce() {
        scan();
    }
}
