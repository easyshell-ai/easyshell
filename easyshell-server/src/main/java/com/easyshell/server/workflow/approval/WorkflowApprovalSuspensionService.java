package com.easyshell.server.workflow.approval;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.workflow.engine.NodeContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;

/** 普通审批创建和 attempt 绑定的唯一短事务，不涉及派发风险门禁。 */
@Service
@Slf4j
public class WorkflowApprovalSuspensionService {
    private final WorkflowApprovalAttemptBinding binding;
    private final TransactionTemplate transactions;

    public WorkflowApprovalSuspensionService(WorkflowApprovalAttemptBinding binding, PlatformTransactionManager manager) {
        this.binding = binding;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public long suspend(NodeContext ctx, String title, String detail, String role, int timeoutSec) {
        // 超时线程使用同一把锁：它不能在提交中途认定超时并留下孤儿审批。
        synchronized (ctx.nodeCancelled()) {
            Long id = transactions.execute(tx -> suspendInTransaction(ctx, title, detail, role, timeoutSec));
            if (id == null) throw new IllegalStateException("审批事务没有返回结果");
            return id;
        }
    }

    private long suspendInTransaction(NodeContext ctx, String title, String detail, String role, int timeoutSec) {
        WorkflowNodeRun attempt = ctx.nodeRun();
        String runId = ctx.run().getId();
        if (ctx.isCancelled() || attempt.getId() == null || attempt.getAttempt() == null || timeoutSec <= 0) {
            throw new IllegalStateException("审批 attempt 已取消或参数无效");
        }
        long id = binding.createAndBind(runId, attempt.getId(), ctx.node().getKey(), attempt.getAttempt(),
                "approval", title, detail, role, timeoutSec,
                run -> {
                    if (!"running".equals(run.getStatus()) || !Boolean.FALSE.equals(run.getCancelRequested()) || ctx.isCancelled())
                        throw new IllegalStateException("运行不能挂起");
                }, locked -> {
                    if (!"running".equals(locked.getStatus()) || !"approval".equals(locked.getNodeType())
                            || locked.getApprovalId() != null || locked.getTaskId() != null || ctx.isCancelled())
                        throw new IllegalStateException("attempt 不能挂起");
                }, ignored -> { }, () -> {
                    if (ctx.isCancelled()) throw new IllegalStateException("审批绑定失败");
                });
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    ctx.emit("APPROVAL_REQUESTED", "等待审批 approval=" + id);
                    ctx.emit("NODE_WAITING", "等待审批 approval=" + id);
                } catch (RuntimeException e) {
                    log.warn("审批事件发送失败: run={} approval={}", runId, id, e);
                }
            }
        });
        return id;
    }
}
