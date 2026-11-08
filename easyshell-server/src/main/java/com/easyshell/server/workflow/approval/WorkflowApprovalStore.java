package com.easyshell.server.workflow.approval;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.repository.WorkflowApprovalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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

    /** 显式 REQUIRES_NEW 事务边界（引擎线程池里手动装配的对象图不保证经过 Spring 代理） */
    private final TransactionTemplate transactionTemplate;

    public WorkflowApprovalStore(WorkflowApprovalRepository approvalRepository,
                                 PlatformTransactionManager transactionManager) {
        this.approvalRepository = approvalRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public WorkflowApproval create(String runId, String nodeKey, String nodeType, String title,
                                   String detailJson, String riskLevel, String riskMatches,
                                   String approverRole, int timeoutSec) {
        return transactionTemplate.execute(status -> {
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
        });
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
     * @return true = 决策已落库；false = 已有人决策过或版本已被推进（调用方转 409）
     */
    public boolean decide(Long approvalId, String status, Long decidedBy, String comment) {
        return transactionTemplate.execute(tx -> {
            Optional<WorkflowApproval> current = approvalRepository.findById(approvalId);
            if (current.isEmpty()) {
                return false;
            }
            WorkflowApproval approval = current.get();
            if (!"pending".equals(approval.getStatus())) {
                return false;
            }
            return approvalRepository.decideIfPending(approvalId, status, decidedBy, LocalDateTime.now(),
                    comment, approval.getVersion()) == 1;
        });
    }

    /**
     * 看门狗置超时（§3.10 四级超时之审批级）。
     *
     * @return true = 本线程置了 timeout；false = 已被人工决策抢先
     */
    public boolean expire(Long approvalId) {
        return transactionTemplate.execute(tx -> {
            Optional<WorkflowApproval> current = approvalRepository.findById(approvalId);
            if (current.isEmpty() || !"pending".equals(current.get().getStatus())) {
                return false;
            }
            return approvalRepository.expireIfPending(approvalId, current.get().getVersion()) == 1;
        });
    }
}
