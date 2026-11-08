package com.easyshell.server.workflow.node;

import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * condition 节点执行器（§2.11：节点的 expression 只决定 true/false handle 出边，
 * <b>边的 condition 才是唯一门控</b>；本节点的求值结果作为 output.result 与边条件输入）。
 *
 * <p><b>fail-closed 语义（§2.5）</b>：求值失败/超时/含不允许的语法时，节点记 NODE_WARNING
 * 并走 <b>false 分支</b>（output.result=false + warning_message 落库 + 审计 warning），
 * 绝不放行——依赖它的边条件 {@code nodes.x.output.result == true} 自然不通行，
 * 于是下游被跳过。这与 AI Chat {@code ConditionEvaluator} 的 fail-open（{@code return true}）
 * 是刻意分叉。</p>
 */
@Slf4j
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    private final ExpressionEvaluator expressionEvaluator;

    public ConditionNodeExecutor(ExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    @Override
    public NodeType supports() {
        return NodeType.CONDITION;
    }

    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        String expression = node.configString("expression");
        if (expression == null || expression.isBlank()) {
            issues.error("NODE_CONFIG_INVALID", "condition 节点缺少 expression", node.getKey());
            return;
        }
        for (String error : expressionEvaluator.staticAnalyze(expression)) {
            issues.error("EXPRESSION_SYNTAX", error, node.getKey());
        }
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        WorkflowNode node = ctx.node();
        String expression = node.configString("expression");
        if (expression == null || expression.isBlank()) {
            throw new NodeFailedException("NODE_CONFIG_INVALID", "condition 节点缺少 expression");
        }
        boolean result;
        try {
            result = expressionEvaluator.evaluate(expression, ctx.resolver());
        } catch (RuntimeException e) {
            // fail-closed：不可求值 → false 分支 + warning（§2.5）
            ctx.variables().addWarning("条件求值失败（" + e.getMessage() + "），按 false 分支处理");
            ctx.emit("NODE_WARNING", "条件求值失败，按 false 分支处理");
            result = false;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", result);
        ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }
}
