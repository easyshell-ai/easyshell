package com.easyshell.server.workflow.approval;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.repository.WorkflowNodeRunRepository;
import com.easyshell.server.repository.WorkflowRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Creates an approval only as part of binding it to a locked running attempt. */
@Service
public class WorkflowApprovalAttemptBinding {
    private final WorkflowRunRepository runs;
    private final WorkflowNodeRunRepository nodes;
    private final WorkflowApprovalStore approvals;
    private final EntityManager entityManager;

    public WorkflowApprovalAttemptBinding(WorkflowRunRepository runs, WorkflowNodeRunRepository nodes,
                                          WorkflowApprovalStore approvals, EntityManager entityManager) {
        this.runs = runs;
        this.nodes = nodes;
        this.approvals = approvals;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long createAndBind(String runId, String nodeRunId, String nodeKey, int attempt, String nodeType,
                              String title, String detail, String role, int timeoutSec,
                              Consumer<WorkflowRun> validateRun, Consumer<WorkflowNodeRun> validateAttempt,
                              LongConsumer afterInsert, Runnable afterBind) {
        WorkflowRun run = runs.lockById(runId).orElseThrow(() -> new IllegalStateException("运行不存在"));
        entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);
        validateRun.accept(run);
        WorkflowNodeRun node = nodes.lockRunningAttempt(nodeRunId, runId, nodeKey, attempt)
                .orElseThrow(() -> new IllegalStateException("运行中的 attempt 不存在"));
        entityManager.refresh(node, LockModeType.PESSIMISTIC_WRITE);
        validateAttempt.accept(node);
        WorkflowApproval approval = approvals.createInTransaction(runId, nodeKey, nodeType, title, detail,
                null, null, role, timeoutSec);
        if (approval == null || approval.getId() == null || approval.getId() <= 0) {
            throw new IllegalStateException("审批创建失败");
        }
        long id = approval.getId();
        afterInsert.accept(id);
        int updated = switch (nodeType) {
            case "approval" -> nodes.suspendApprovalAttempt(nodeRunId, runId, nodeKey, attempt, id);
            case "script-exec" -> nodes.suspendDispatchAttempt(nodeRunId, runId, nodeKey, attempt, id);
            default -> throw new IllegalArgumentException("不支持的审批节点类型");
        };
        if (updated != 1) throw new IllegalStateException("script-exec".equals(nodeType)
                ? "Cannot suspend dispatch attempt" : "审批绑定失败");
        afterBind.run();
        return id;
    }
}
