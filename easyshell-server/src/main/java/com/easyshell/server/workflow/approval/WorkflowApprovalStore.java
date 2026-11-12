package com.easyshell.server.workflow.approval;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 审批行存储（DB 是唯一事实源，§3.8）。
 *
 * <p>创建时渲染一次 {@code detail_json} 并<b>永久不可变</b>（§7.1.2）；决策是带
 * version 乐观锁的条件 UPDATE，重复决策影响 0 行（409 APPROVAL_DECIDED）。</p>
 */
@Slf4j
@Service
public class WorkflowApprovalStore {

    private final WorkflowApprovalRepository approvalRepository;
    private final EntityManager entityManager;

    private final TransactionTemplate requiredTransaction;

    public WorkflowApprovalStore(WorkflowApprovalRepository approvalRepository,
                                 EntityManager entityManager,
                                 PlatformTransactionManager transactionManager) {
        this.approvalRepository = approvalRepository;
        this.entityManager = entityManager;
        this.requiredTransaction = new TransactionTemplate(transactionManager);
    }

    public WorkflowApproval create(String runId, String nodeKey, String nodeType, String title,
                                   String detailJson, String riskLevel, String riskMatches,
                                   String approverRole, int timeoutSec) {
        throw new IllegalStateException("审批必须通过挂起服务与 attempt 原子绑定");
    }

    /** 创建审批并参与调用方事务；不隐式开启独立事务。 */
    WorkflowApproval createInTransaction(String runId, String nodeKey, String nodeType, String title,
                                                String detailJson, String riskLevel, String riskMatches,
                                                String approverRole, int timeoutSec) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("审批创建必须在调用方事务内执行");
        }
        LocalDateTime now = LocalDateTime.now();
        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId(runId);
        approval.setNodeKey(nodeKey);
        approval.setNodeType(nodeType);
        approval.setTitle(title);
        approval.setDetailJson(detailJson);
        approval.setRiskLevel(riskLevel);
        approval.setRiskMatches(riskMatches);
        approval.setApproverRole(approverRole == null || approverRole.isBlank() ? "ADMIN" : approverRole);
        approval.setStatus("pending");
        approval.setExpiresAt(now.plusSeconds(Math.max(60, timeoutSec)));
        return approvalRepository.save(approval);
    }

    public Optional<WorkflowApproval> findById(Long approvalId) {
        return approvalRepository.findById(approvalId);
    }

    public Optional<WorkflowApproval> findByRunAndNode(String runId, String nodeKey) {
        return approvalRepository.findByRunIdAndNodeKey(runId, nodeKey);
    }

    /**
     * 决策端点唯一写入口（MF-8：只做状态迁移）。
     *
     * @return 决策后的审批行（属于调用方事务，尚未提交）；null = 已有人决策过
     *         或版本已被推进（调用方转 409 APPROVAL_DECIDED）。
     *         <p>为什么返回整行而不是布尔：{@code @Modifying} 批量 UPDATE <b>不进</b>持久化
     *         上下文，调用方（决策端点）手里那行还是决策前的旧快照——直接把它回给前端，
     *         响应体里的 status 会是 pending，用户以为没批成功。这里 refresh 出真实状态
     *         （含被条件 UPDATE 推进过的 version）再交出去，调用方不需要、也不能再就地改它：
     *         那行在调用方事务里是托管实体，改它会在提交时被 flush 成一次假的乐观锁冲突。</p>
     */
    public WorkflowApproval decide(Long approvalId, String status, Long decidedBy, String comment) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("审批决策必须在调用方事务内执行");
        }
        Optional<WorkflowApproval> current = approvalRepository.findById(approvalId);
        if (current.isEmpty()) {
            return null;
        }
        WorkflowApproval approval = current.get();
        if (!lockRunAndRefreshApproval(approval)) {
            return null;
        }
        if (!"pending".equals(approval.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = approvalRepository.decideIfPending(approvalId, status, decidedBy, now,
                comment, approval.getVersion());
        if (updated != 1) {
            return null;
        }
        entityManager.refresh(approval);
        return approval;
    }

    /**
     * 看门狗置超时（§3.10 四级超时之审批级）。
     *
     * @return true = 本线程置了 timeout；false = 已被人工决策抢先
     */
    public boolean expire(Long approvalId) {
        return Boolean.TRUE.equals(requiredTransaction.execute(tx -> {
            Optional<WorkflowApproval> current = approvalRepository.findById(approvalId);
            if (current.isEmpty() || !lockRunAndRefreshApproval(current.get())
                    || !"pending".equals(current.get().getStatus())) {
                return false;
            }
            return approvalRepository.expireIfPending(approvalId, current.get().getVersion()) == 1;
        }));
    }

    /** 先读不可变的 runId，再锁 run；等待期间审批可能已改变，必须刷新快照。 */
    private boolean lockRunAndRefreshApproval(WorkflowApproval approval) {
        if (entityManager.find(WorkflowRun.class, approval.getRunId(), LockModeType.PESSIMISTIC_WRITE) == null) {
            return false;
        }
        entityManager.refresh(approval);
        return true;
    }
}
