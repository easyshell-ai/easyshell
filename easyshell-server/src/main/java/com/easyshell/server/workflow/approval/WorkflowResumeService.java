package com.easyshell.server.workflow.approval;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.workflow.engine.WorkflowEngine;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
        if (!approvalStore.decide(approvalId, decision, decidedBy, comment)) {
            throw new BusinessException(409, "APPROVAL_DECIDED");
        }

        // 六类高危事件之一：同步审计，写失败即使业务失败（C-13）
        auditLogService.logSync(decidedBy, "SYSTEM",
                "approved".equals(decision) ? "WORKFLOW_APPROVE" : "WORKFLOW_REJECT",
                "workflow_approval", String.valueOf(approvalId),
                "run=" + approval.getRunId() + ", node=" + approval.getNodeKey()
                        + ", comment=" + comment, null, "success");

        // 自审批约束（S1）：默认开启，decided_by == started_by 时允许但记 WARNING
        if (properties.getApproval().isRequireDifferentApprover()) {
            runRepository.findById(approval.getRunId()).ifPresent(run -> {
                if (decidedBy != null && decidedBy.equals(run.getStartedBy())) {
                    auditLogService.logSync(decidedBy, "SYSTEM", "WORKFLOW_SELF_APPROVAL",
                            "workflow_approval", String.valueOf(approvalId),
                            "审批人即发起人: run=" + approval.getRunId(), null, "warning");
                }
            });
        }

        String runId = approval.getRunId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            submitResume(runId);
                        }
                    });
        } else {
            submitResume(runId);
        }
        return approval;
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
    public void scanWakeups() {
        List<com.easyshell.server.model.entity.WorkflowNodeRun> waiting = nodeRunRepository.findByStatus("waiting");
        Set<String> runIds = new java.util.HashSet<>();
        for (com.easyshell.server.model.entity.WorkflowNodeRun row : waiting) {
            runIds.add(row.getRunId());
        }
        for (String runId : runIds) {
            try {
                resumeIfReady(runId);
            } catch (Exception e) {
                log.warn("兜底恢复失败: run={} err={}", runId, e.getMessage());
            }
        }
        // 被 tryLock 拒绝登记的 run（§3.8 v3 补充：不静默丢弃）
        for (String runId : engine.pendingWakeups()) {
            Optional<WorkflowRun> run = runRepository.findById(runId);
            if (run.isPresent() && !TERMINAL_RUN.contains(run.get().getStatus())) {
                try {
                    resumeIfReady(runId);
                } catch (Exception e) {
                    log.warn("兜底恢复失败: run={} err={}", runId, e.getMessage());
                }
            }
        }
    }

    /**
     * 恢复一个 run（同步，测试与扫描器共用）。
     *
     * <p>本 run 下全部 waiting 节点都已决策才唤醒；任一审pending 都整体放弃，
     * 等下一轮扫描——避免"唤醒一半"的中间态。</p>
     */
    public void resumeIfReady(String runId) {
        WorkflowRun run = runRepository.findById(runId).orElse(null);
        if (run == null || TERMINAL_RUN.contains(run.getStatus())) {
            return;
        }
        List<com.easyshell.server.model.entity.WorkflowNodeRun> waiting =
                nodeRunRepository.findByRunIdAndStatus(runId, "waiting");
        if (waiting.isEmpty()) {
            return;
        }
        for (com.easyshell.server.model.entity.WorkflowNodeRun row : waiting) {
            Optional<WorkflowApproval> approval = approvalStore.findByRunAndNode(runId, row.getNodeKey());
            if (approval.isEmpty() || "pending".equals(approval.get().getStatus())) {
                return;   // 还有未决策的审批
            }
        }
        engine.resumeRun(runId);
    }
}
