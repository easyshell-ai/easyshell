package com.easyshell.server.workflow.gate;

import com.easyshell.server.workflow.approval.WorkflowApprovalAttemptBinding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomic persistence only; this service never authorizes or dispatches a task. */
@Service
public class WorkflowDispatchApprovalSuspensionService {
    private final WorkflowApprovalAttemptBinding attemptBinding;
    private final WorkflowDispatchApprovalBindingService bindings;

    public WorkflowDispatchApprovalSuspensionService(WorkflowApprovalAttemptBinding attemptBinding,
                                                     WorkflowDispatchApprovalBindingService bindings) {
        this.attemptBinding = attemptBinding;
        this.bindings = bindings;
    }

    /** The caller owns the transaction and must not acquire a node lock before the run lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long suspend(String nodeKey, WorkflowDispatchApprovalBindingService.Intent intent, int approvalTimeoutSec) {
        return suspend(nodeKey, intent, approvalTimeoutSec, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long suspend(String nodeKey, WorkflowDispatchApprovalBindingService.Intent intent, int approvalTimeoutSec,
                        String filteredDetail) {
        if (intent == null || nodeKey == null || nodeKey.isBlank() || intent.runId() == null
                || intent.runId().isBlank() || intent.nodeRunId() == null || intent.nodeRunId().isBlank()
                || intent.attempt() <= 0 || intent.userId() <= 0 || approvalTimeoutSec <= 0) {
            throw new IllegalArgumentException("Missing dispatch approval facts");
        }
        return attemptBinding.createAndBind(intent.runId(), intent.nodeRunId(), nodeKey, intent.attempt(),
                "script-exec", "Dispatch approval", filteredDetail, "ADMIN", approvalTimeoutSec,
                run -> {
                    if (!"running".equals(run.getStatus()) || !Boolean.FALSE.equals(run.getCancelRequested())
                            || run.getStartedBy() == null || run.getStartedBy().longValue() != intent.userId())
                        throw new IllegalStateException("Workflow cannot be suspended");
                }, node -> {
                    if (!"running".equals(node.getStatus()) || !intent.runId().equals(node.getRunId())
                            || !nodeKey.equals(node.getNodeKey()) || !Integer.valueOf(intent.attempt()).equals(node.getAttempt())
                            || !"script-exec".equals(node.getNodeType()) || node.getTaskId() != null || node.getApprovalId() != null)
                        throw new IllegalStateException("Dispatch attempt cannot be suspended");
                }, id -> bindings.create(id, intent), () -> { });
    }
}
