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

import java.util.List;
import java.util.Map;

/**
 * transform 节点执行器（工作流 2.0 §2.1 / §2.5）。
 *
 * <p>{@code config.mappings = [{name, value}]}，1..50 行，name 必须是安全的小写标识符
 * {@code ^[a-z][a-z0-9_]{0,63}$}，且行内去重。值恰好是 {@code ${path}} 时保留原类型，
 * 否则模板插值；任何引用不存在都 fail-closed（错误码保留
 * {@link ExpressionException#REFERENCE_NOT_FOUND}）。</p>
 */
@Component
public class TransformNodeExecutor implements NodeExecutor {

    private static final int MIN_ROWS = 1;
    private static final int MAX_ROWS = 50;

    private final ExpressionEvaluator expressionEvaluator;

    public TransformNodeExecutor(ExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    @Override
    public NodeType supports() {
        return NodeType.TRANSFORM;
    }

    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        MappingRows.validate(node, "mappings", "transform", MappingRows.NAME_PATTERN,
                MIN_ROWS, MAX_ROWS, true, issues);
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        List<MappingRows.Row> rows = MappingRows.requireValid(ctx.node(), "mappings", "transform",
                MappingRows.NAME_PATTERN, MIN_ROWS, MAX_ROWS, true);
        Map<String, Object> output;
        try {
            output = MappingRows.resolve(rows, expressionEvaluator, ctx.resolver(),
                    ctx.variables()::addWarning);
        } catch (ExpressionException e) {
            throw new NodeFailedException(e.getCode(), e.getMessage(), e);
        }
        ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }
}
