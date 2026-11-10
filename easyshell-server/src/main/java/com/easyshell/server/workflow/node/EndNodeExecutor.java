package com.easyshell.server.workflow.node;

import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * end 节点执行器（工作流 2.0 §2.1 / §2.4）。
 *
 * <p>{@code config.outputs = [{name, value}]}：值恰好是 {@code ${path}} 时保留解析结果的
 * 原类型，否则按模板插值。解析出的 {@code {name: value}} 就是工作流最终输出
 * （{@code WorkflowEngine.buildRunOutput} 在本节点有 output_json 时直接用它，
 * 不再汇总各节点输出）。引用不存在 fail-closed。</p>
 */
@Component
public class EndNodeExecutor implements NodeExecutor {

    private static final int MAX_OUTPUTS = 50;

    private final ExpressionEvaluator expressionEvaluator;

    public EndNodeExecutor(ExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    @Override
    public NodeType supports() {
        return NodeType.END;
    }

    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        // end 允许空 outputs（等价于空对象输出）；名字只要求非空、去重
        MappingRows.validate(node, "outputs", "end", null, 0, MAX_OUTPUTS, false, issues);
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        List<MappingRows.Row> rows = MappingRows.requireValid(ctx.node(), "outputs", "end",
                null, 0, MAX_OUTPUTS, false);
        Map<String, Object> output;
        try {
            output = MappingRows.resolve(rows, expressionEvaluator, ctx.resolver(),
                    ctx.variables()::addWarning);
        } catch (ExpressionException e) {
            throw new NodeFailedException(e.getCode(), e.getMessage(), e);
        }
        if (output == null) {
            output = new LinkedHashMap<>();
        }
        ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }
}
