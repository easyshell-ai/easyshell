package com.easyshell.server.workflow.engine;

import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.graph.ValidationIssue;

import java.util.List;

/**
 * 节点执行器契约（§3.3）。每类节点一个实现，由 {@link NodeExecutorRegistry} 按类型分派。
 */
public interface NodeExecutor {

    NodeType supports();

    /** 发布期配置校验（错误码进 issues 面板） */
    default void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
    }

    /**
     * 阻塞式执行。返回 WAITING 表示"本节点自行挂起"，实现必须已落库且不占线程；
     * 抛任何异常由调用方统一转 FAILED；必须在长操作中点检查 {@code ctx.isCancelled()}。
     */
    NodeOutcome execute(NodeContext ctx);

    /** 可重试性判定（§3.12：超时/网络/IO/agent 掉线可重试；类型/校验/BANNED/权限/attempt 冲突不可重试） */
    default boolean retryable(Throwable t) {
        return false;
    }
}
