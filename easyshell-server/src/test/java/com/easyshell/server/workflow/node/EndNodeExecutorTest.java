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

@DisplayName("end 节点 — 最终输出映射 / 类型保留 / fail-closed")
class EndNodeExecutorTest {

    private EndNodeExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new EndNodeExecutor(new ExpressionEvaluator(new ObjectMapper()));
    }

    private WorkflowNode node(String key, List<Map<String, Object>> outputs) {
        WorkflowNode node = new WorkflowNode();
        node.setKey(key);
        node.setType("end");
        node.setName(key);
        node.setConfig(new LinkedHashMap<>(Map.of("outputs", outputs)));
        return node;
    }

    private NodeContext context(WorkflowNode node, VariableStore variables) {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_end");
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(42L);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_end");
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey(node.getKey());
        nodeRun.setNodeType("end");
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
    @DisplayName("输出映射：纯 ${path} 保留类型，模板插值成字符串")
    void resolvesOutputMapping() {
        WorkflowNode end = node("finish", List.of(
                Map.of("name", "payload", "value", "${nodes.ai.output.text}"),
                Map.of("name", "ok", "value", "${nodes.gate.output.result}"),
                Map.of("name", "summary", "value", "结论: ${nodes.ai.output.text}")));

        VariableStore variables = new VariableStore();
        variables.putNodeOutput("ai", null, Map.of("text", "disk ok"));
        variables.putNodeOutput("gate", null, Map.of("result", true));

        assertThat(executor.execute(context(end, variables))).isEqualTo(NodeOutcome.SUCCEEDED);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) variables.nodeOutput("finish").value();
        assertThat(output).containsExactlyInAnyOrderEntriesOf(Map.of(
                "payload", "disk ok", "ok", true, "summary", "结论: disk ok"));
    }

    @Test
    @DisplayName("大输出（>8KB）走专用通道：node preview 仍截断，runOutput 保留完整对象")
    void largeOutputKeepsDedicatedRunOutput() {
        String big = "x".repeat(9000);
        WorkflowNode end = node("finish", List.of(
                Map.of("name", "summary", "value", "${run.input.blob}")));
        VariableStore variables = new VariableStore();
        variables.putInput("blob", big);

        executor.execute(context(end, variables));

        assertThat(variables.nodeOutput("finish").json()).doesNotContain(big);
        @SuppressWarnings("unchecked")
        Map<String, Object> runOutput = (Map<String, Object>) variables.runOutput();
        assertThat(runOutput.get("summary")).isEqualTo(big);
    }

    @Test
    @DisplayName("引用不存在：EXPRESSION_REFERENCE_NOT_FOUND")
    void missingReferenceFailsClosed() {
        WorkflowNode end = node("finish", List.of(
                Map.of("name", "summary", "value", "${nodes.ghost.output.text}")));

        assertThatThrownBy(() -> executor.execute(context(end, new VariableStore())))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ExpressionException.REFERENCE_NOT_FOUND));
    }

    @Test
    @DisplayName("校验：重复 name / 超过 50 行 / 非字符串 value 都是 ERROR")
    void validationRejectsBadRows() {
        WorkflowNode duplicate = node("finish", List.of(
                Map.of("name", "a", "value", "x"),
                Map.of("name", "a", "value", "y")));
        ValidationIssue.Sink sink = new ValidationIssue.Sink();
        executor.validate(duplicate, new WorkflowDefinition(), sink);
        assertThat(sink.hasError()).isTrue();

        List<Map<String, Object>> tooMany = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> Map.<String, Object>of("name", "n" + i, "value", "v"))
                .toList();
        ValidationIssue.Sink sink2 = new ValidationIssue.Sink();
        executor.validate(node("finish", tooMany), new WorkflowDefinition(), sink2);
        assertThat(sink2.hasError()).isTrue();

        WorkflowNode nonString = node("finish", List.of(Map.of("name", "a", "value", 1)));
        ValidationIssue.Sink sink3 = new ValidationIssue.Sink();
        executor.validate(nonString, new WorkflowDefinition(), sink3);
        assertThat(sink3.hasError()).isTrue();
    }
}
