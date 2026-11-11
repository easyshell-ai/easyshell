package com.easyshell.server.workflow.approval;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.engine.WorkflowRecoveryClaimService;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 审批挂起/恢复（§3.8：DB 行唯一事实源 + 同 JVM 事件 + 10s 扫描兜底）。
 *
 * <p>三条唤醒路径：① 同 JVM：决策端点 afterCommit 调 {@link #submitResume}；
 * ② 跨 JVM：Redis PUBLISH（M2）；③ 兜底：{@link #scanWakeups} 每 10s 扫 DB（派生式）。
 * <b>取消与恢复的竞态有确定解</b>：决策用 {@code status='pending' AND version=期望值} 的条件
 * UPDATE，取消把 approval 置 cancelled——先到者赢，后到者影响 0 行；resume 的
 * {@code resumeIfWaiting} 同样带状态条件，run 已终态时影响 0 行，幂等返回。</p>
 */
@Slf4j
@Service
public class WorkflowResumeService {

    private static final List<String> TERMINAL_RUN = List.of("succeeded", "failed", "cancelled", "timeout");

    private final WorkflowApprovalStore approvalStore;
    private final WorkflowRunRepository runRepository;
    private final WorkflowNodeRunRepository nodeRunRepository;
    private final WorkflowEngine engine;
    private final AuditLogService auditLogService;
    private final WorkflowProperties properties;

    private WorkflowRecoveryClaimService recoveryClaims;

    private static final int DURABLE_SCAN_LIMIT = 100;
    // Discovery position only, never execution authority. Failed candidates also advance it.
    private String durableScanAfterRunId;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecoveryClaims(WorkflowRecoveryClaimService recoveryClaims) {
        this.recoveryClaims = recoveryClaims;
    }

    /** 显式事务边界（REQUIRED）：决策与审计同一个提交单元 */
    private final TransactionTemplate transactionTemplate;

    public WorkflowResumeService(WorkflowApprovalStore approvalStore,
                                 WorkflowRunRepository runRepository,
                                 WorkflowNodeRunRepository nodeRunRepository,
                                 WorkflowEngine engine,
                                 AuditLogService auditLogService,
                                 WorkflowProperties properties,
                                 PlatformTransactionManager transactionManager) {
        this.approvalStore = approvalStore;
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.engine = engine;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 决策端点唯一入口（MF-8：只做状态迁移，不跑 drive）。
     *
     * <p>同一事务里做条件 UPDATE approval + 同步审计；恢复动作注册在 afterCommit——
     * 事务提交后才唤醒，避免"审批已写但 run 还看不到"的窗口。</p>
     *
     * @param decision approved / rejected
     * @return 被决策的审批行
     * @throws BusinessException 409 APPROVAL_DECIDED（重复决策）/ 404 APPROVAL_NOT_FOUND
     */
    public WorkflowApproval decide(Long approvalId, String decision, Long decidedBy, String comment) {
        return transactionTemplate.execute(status ->
                decideInTransaction(approvalId, decision, decidedBy, comment));
    }

    private WorkflowApproval decideInTransaction(Long approvalId, String decision, Long decidedBy,
                                                 String comment) {
        WorkflowApproval approval = approvalStore.findById(approvalId)
                .orElseThrow(() -> new BusinessException(404, "APPROVAL_NOT_FOUND"));
        if (!"approved".equals(decision) && !"rejected".equals(decision)) {
            throw new BusinessException(400, "WORKFLOW_PARAM_INVALID: decision 必须是 approved / rejected");
        }
        // decide 返回决策后的真实行（refresh 过，含推进后的 version）。
        // 不要就地改 approval 再返回：它在本地事务里是托管实体，提交时的 flush 会拿着
        // 旧 version 去 UPDATE，把一次成功的决策报成假的乐观锁冲突。
        WorkflowApproval decided = approvalStore.decide(approvalId, decision, decidedBy, comment);
        if (decided == null) {
            throw new BusinessException(409, "APPROVAL_DECIDED");
        }

        // 六类高危事件之一：同步审计，写失败即使业务失败（C-13）
        auditLogService.logSync(decidedBy, "SYSTEM",
                "approved".equals(decision) ? "WORKFLOW_APPROVE" : "WORKFLOW_REJECT",
                "workflow_approval", String.valueOf(approvalId),
                "run=" + decided.getRunId() + ", node=" + decided.getNodeKey()
                        + ", decision=" + decision, null, "success");

        // 自审批约束（S1）：默认开启，decided_by == started_by 时允许但记 WARNING
        if (properties.getApproval().isRequireDifferentApprover()) {
            runRepository.findById(decided.getRunId()).ifPresent(run -> {
                if (decidedBy != null && decidedBy.equals(run.getStartedBy())) {
                    auditLogService.logSync(decidedBy, "SYSTEM", "WORKFLOW_SELF_APPROVAL",
                            "workflow_approval", String.valueOf(approvalId),
                            "审批人即发起人: run=" + approval.getRunId(), null, "warning");
                }
            });
        }

        String runId = decided.getRunId();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("审批决策必须支持事务提交后恢复");
        }
        TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        submitResume(runId);
                    }
                });
        return decided;
    }

    /** 同 JVM 事件入口（唤醒路径①） */
    public void submitResume(String runId) {
        engine.submitResume(runId);
    }

    /**
     * 兜底扫描（唤醒路径③）：每 10s 找出"还有 waiting 节点"的 run 尝试恢复。
     *
     * <p>派生式（MF-9）：waiting 集合从 {@code workflow_node_run} 派生，run 级不落
     * waiting_node_key 字段，因此重启/漏事件都不会丢挂起状态。</p>
     */
    @Scheduled(fixedDelayString = "${easyshell.workflow.engine.wakeup-interval-sec:10}000",
            initialDelay = 5000)
    public synchronized void scanWakeups() {
        if (!properties.getEngine().isAutomaticRecoveryEnabled()
                && !properties.getEngine().isDurableResumeEnabled()) {
            return;
        }
        if (properties.getEngine().isDurableResumeEnabled()) {
            scanDurableWakeups();
            return;
        }
        List<com.easyshell.server.model.entity.WorkflowNodeRun> waiting = nodeRunRepository.findByStatus("waiting");
        Set<String> runIds = new java.util.HashSet<>();
        for (com.easyshell.server.model.entity.WorkflowNodeRun row : waiting) {
            runIds.add(row.getRunId());
        }
        for (String runId : runIds) {
            try {
                resumeIfReady(runId);
            } catch (Exception e) {
                log.warn("兜底恢复失败: run={}", safeRunId(runId));
            }
        }
        // 被 tryLock 拒绝登记的 run（§3.8 v3 补充：不静默丢弃）
        for (String runId : engine.pendingWakeups()) {
            Optional<WorkflowRun> run = runRepository.findById(runId);
            if (run.isPresent() && !TERMINAL_RUN.contains(run.get().getStatus())) {
                try {
                    resumeIfReady(runId);
                } catch (Exception e) {
                    log.warn("兜底恢复失败: run={}", safeRunId(runId));
                }
            }
        }
    }

    private void scanDurableWakeups() {
        List<String> candidates;
        try {
            candidates = nodeRunRepository.findWaitingRunIdsAfter(durableScanAfterRunId,
                    org.springframework.data.domain.PageRequest.of(0, DURABLE_SCAN_LIMIT));
        } catch (RuntimeException failure) {
            log.warn("持久审批唤醒候选读取失败");
            return;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String runId : candidates.stream().limit(DURABLE_SCAN_LIMIT).toList()) {
            durableScanAfterRunId = runId;
            if (!seen.add(runId)) continue;
            try {
                resumeIfReady(runId);
            } catch (RuntimeException failure) {
                // Submission rejection leaves waiting untouched; a later cursor pass retries it.
                log.warn("持久审批唤醒提交失败: run={}", safeRunId(runId));
            }
        }
        if (candidates.size() < DURABLE_SCAN_LIMIT) durableScanAfterRunId = null;
    }

    private static String safeRunId(String runId) {
        return runId != null && runId.matches("[A-Za-z0-9_-]{1,64}") ? runId : "<invalid>";
    }

    /**
     * 恢复一个 run（durable 仅异步提交候选，legacy 同步执行）。
     *
     * <p>本 run 下全部 waiting 节点都已决策才唤醒；任一审pending 都整体放弃，
     * 等下一轮扫描——避免"唤醒一半"的中间态。</p>
     */
    public void resumeIfReady(String runId) {
        WorkflowRun run = runRepository.findById(runId).orElse(null);
        if (run == null || TERMINAL_RUN.contains(run.getStatus())) {
            return;
        }
        if (properties.getEngine().isDurableResumeEnabled()
                && (!"waiting".equals(run.getStatus()) || recoveryClaims == null
                    || recoveryClaims.hasActiveBatch(runId))) {
            return;
        }
        List<com.easyshell.server.model.entity.WorkflowNodeRun> waiting =
                nodeRunRepository.findByRunIdAndStatus(runId, "waiting");
        if (waiting.isEmpty()) {
            return;
        }
        for (com.easyshell.server.model.entity.WorkflowNodeRun row : waiting) {
            if (row.getApprovalId() == null) {
                return;
            }
            Optional<WorkflowApproval> approval = approvalStore.findById(row.getApprovalId());
            if (approval.isEmpty() || !java.util.Objects.equals(runId, approval.get().getRunId())
                    || !java.util.Objects.equals(row.getNodeKey(), approval.get().getNodeKey())
                    || !java.util.Objects.equals(row.getNodeType(), approval.get().getNodeType())
                    || approval.get().getStatus() == null
                    || !Set.of("approved", "rejected", "timeout", "cancelled").contains(approval.get().getStatus())) {
                return;   // 还有未决策的审批
            }
        }
        if (properties.getEngine().isDurableResumeEnabled()) {
            engine.submitResume(runId);
        } else {
            engine.resumeRun(runId);
        }
    }
}
