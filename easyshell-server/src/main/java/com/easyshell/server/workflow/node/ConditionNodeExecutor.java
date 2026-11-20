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
 * condition 节点执行器：表达式结果决定 true/false handle，求值异常则节点失败，
 * 不能将异常当成 false 而执行 ELSE。
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
            throw new NodeFailedException("CONDITION_EVALUATION_FAILED", "条件求值失败", e);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", result);
        ctx.variables().putNodeOutput(node.getKey(), node.getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }
}
