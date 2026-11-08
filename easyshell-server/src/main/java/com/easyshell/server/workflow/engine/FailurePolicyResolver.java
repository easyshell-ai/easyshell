package com.easyshell.server.workflow.engine;

import com.easyshell.server.workflow.model.FailurePolicy;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowEdge;
import com.easyshell.server.workflow.model.WorkflowNode;

/**
 * 失败策略解析（§2.8）。
 *
 * <p>优先级：边的 {@code onUpstreamFailure} &gt; 节点自身的 {@code onFailure} &gt;
 * definition 的 {@code defaultOnUpstreamFailure} &gt; 硬默认 {@code skip}。
 * 多上游策略不一致时取最严格（ABORT &gt; SKIP &gt; PARTIAL）。
 * <b>未知策略值一律按 ABORT 处理</b>——fail-closed：看不懂的策略不能当成"继续"。</p>
 */
public final class FailurePolicyResolver {

    private FailurePolicyResolver() {
    }

    /** 某条入边对应的失败策略 */
    public static FailurePolicy resolve(WorkflowEdge edge, WorkflowNode downstream, WorkflowDefinition def) {
        String code = null;
        if (edge != null && edge.getOnUpstreamFailure() != null && !edge.getOnUpstreamFailure().isBlank()) {
            code = edge.getOnUpstreamFailure();
        } else if (downstream != null && downstream.getOnFailure() != null && !downstream.getOnFailure().isBlank()) {
            code = downstream.getOnFailure();
        } else if (def != null) {
            code = def.defaultOnUpstreamFailureOrSkip();
        }
        FailurePolicy policy = FailurePolicy.fromCode(code);
        return policy == null ? FailurePolicy.ABORT : policy;   // fail-closed
    }

    /** 节点自身失败时的行为（abort = 终止 run；skip/partial = 记录失败后继续） */
    public static FailurePolicy ownFailure(WorkflowNode node, WorkflowDefinition def) {
        String code = node != null && node.getOnFailure() != null && !node.getOnFailure().isBlank()
                ? node.getOnFailure()
                : def != null ? def.defaultOnUpstreamFailureOrSkip() : FailurePolicy.SKIP.code();
        FailurePolicy policy = FailurePolicy.fromCode(code);
        return policy == null ? FailurePolicy.ABORT : policy;
    }
}
