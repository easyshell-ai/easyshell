package com.easyshell.server.workflow.node;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.expression.ExpressionException;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("transform 节点 — 映射解析 / 类型保留 / fail-closed")
class TransformNodeExecutorTest {

    private ExpressionEvaluator expressionEvaluator;
    private TransformNodeExecutor executor;

    @BeforeEach
    void setUp() {
        expressionEvaluator = new ExpressionEvaluator(new ObjectMapper());
        executor = new TransformNodeExecutor(expressionEvaluator);
    }

    private WorkflowNode node(List<Map<String, Object>> mappings) {
        WorkflowNode node = new WorkflowNode();
        node.setKey("map");
        node.setType("transform");
        node.setName("map");
        node.setConfig(new LinkedHashMap<>(Map.of("mappings", mappings)));
        return node;
    }

    private NodeContext context(WorkflowNode node, VariableStore variables) {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_transform");
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(42L);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_transform");
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey(node.getKey());
        nodeRun.setNodeType("transform");
        nodeRun.setAttempt(1);
        nodeRun.setStatus("running");

        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(List.of(node));
        return new NodeContext(run, nodeRun, node, def, variables,
                Executors.newFixedThreadPool(1), new AtomicBoolean(), new AtomicBoolean(),
                false, event -> {
        }, variables);
    }

    @Test
    @DisplayName("纯 ${path} 保留原类型，模板插值转字符串，字面量原样")
    void resolvesMappingsAndPreservesType() {
        WorkflowNode node = node(List.of(
                Map.of("name", "usage", "value", "${nodes.up.output.usage}"),
                Map.of("name", "payload", "value", "${nodes.up.output}"),
                Map.of("name", "summary", "value", "磁盘 ${nodes.up.output.mount} 使用率 ${nodes.up.output.usage}%"),
                Map.of("name", "padded", "value", "  ${nodes.up.output.mount}  "),
                Map.of("name", "literal", "value", "ok")));

        VariableStore variables = new VariableStore();
        Map<String, Object> upstream = Map.of("usage", 93.5, "mount", "/data");
        variables.putNodeOutput("up", null, upstream);

        assertThat(executor.execute(context(node, variables))).isEqualTo(NodeOutcome.SUCCEEDED);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) variables.nodeOutput("map").value();
        assertThat(output.get("usage")).isEqualTo(93.5);
        assertThat(output.get("payload")).isEqualTo(upstream);
        assertThat(output.get("summary")).isEqualTo("磁盘 /data 使用率 93.5%");
        // 非纯 ${path} 模板保留首尾空白（trim 只用于 exact 判定）
        assertThat(output.get("padded")).isEqualTo("  /data  ");
        assertThat(output.get("literal")).isEqualTo("ok");
    }

    @Test
    @DisplayName("引用不存在的上游输出：EXPRESSION_REFERENCE_NOT_FOUND（不静默变空串）")
    void missingReferenceFailsClosed() {
        WorkflowNode node = node(List.of(
                Map.of("name", "usage", "value", "${nodes.ghost.output.usage}")));

        assertThatThrownBy(() -> executor.execute(context(node, new VariableStore())))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ExpressionException.REFERENCE_NOT_FOUND));
    }

    @Test
    @DisplayName("校验：重复 name / 非法 name / 空 mappings / 非字符串 value 都是 ERROR")
    void validationRejectsBadRows() {
        WorkflowNode duplicate = node(List.of(
                Map.of("name", "a", "value", "x"),
                Map.of("name", "a", "value", "y")));
        ValidationIssue.Sink sink = new ValidationIssue.Sink();
        executor.validate(duplicate, new WorkflowDefinition(), sink);
        assertThat(sink.hasError()).isTrue();
        assertThat(sink.issues()).anySatisfy(i -> assertThat(i.message()).contains("重复 name"));

        WorkflowNode badName = node(List.of(Map.of("name", "Bad-Name", "value", "x")));
        ValidationIssue.Sink sink2 = new ValidationIssue.Sink();
        executor.validate(badName, new WorkflowDefinition(), sink2);
        assertThat(sink2.hasError()).isTrue();

        WorkflowNode empty = node(List.of());
        ValidationIssue.Sink sink3 = new ValidationIssue.Sink();
        executor.validate(empty, new WorkflowDefinition(), sink3);
        assertThat(sink3.hasError()).isTrue();

        WorkflowNode nonString = node(List.of(Map.of("name", "a", "value", 1)));
        ValidationIssue.Sink sink4 = new ValidationIssue.Sink();
        executor.validate(nonString, new WorkflowDefinition(), sink4);
        assertThat(sink4.hasError()).isTrue();
    }
}
