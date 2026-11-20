package com.easyshell.server.workflow.node;

import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeExecutor;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.NodeType;
import com.easyshell.server.workflow.model.VariableSpec;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * start 节点执行器（工作流 2.0 §2.1）。
 *
 * <p>start 不读 {@code config}，只把 {@code definition.variables} 声明的入参
 * 从 {@code run.input.*} 读成节点输出 {@code {name: value}}。入参的必填/类型/默认值
 * 已由 {@code WorkflowRunService.validateInput} 在触发时校验；这里只按声明键取值，
 * 缺失的键跳过（不写入 null，与 {@link com.easyshell.server.workflow.engine.VariableStore}
 * 的"不存在 ≠ null"语义一致）。没有声明入参时输出空 map。</p>
 */
@Component
public class StartNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supports() {
        return NodeType.START;
    }

    /** start 不需要任何 config 字段：入参契约在 definition.variables */
    @Override
    public void validate(WorkflowNode node, WorkflowDefinition def, ValidationIssue.Sink issues) {
        // no-op：start 没有必填 config，也不因多余字段阻断发布
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        WorkflowDefinition def = ctx.definition();
        Map<String, Object> output = new LinkedHashMap<>();
        if (def.getVariables() != null) {
            for (Map.Entry<String, VariableSpec> entry : def.getVariables().entrySet()) {
                String key = entry.getKey();
                Optional<Object> value = ctx.resolver().resolve("run.input." + key);
                value.ifPresent(v -> output.put(key, v));
            }
        }
        ctx.variables().putNodeOutput(ctx.node().getKey(), ctx.node().getOutputVar(), output);
        return NodeOutcome.SUCCEEDED;
    }
}
