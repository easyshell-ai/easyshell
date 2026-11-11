package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.graph.ValidationIssue;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("code 节点 — 运行时不可用时 fail-closed")
class CodeNodeExecutorTest {

    private ExecutorService placeholderPool;
    private CodeNodeExecutor executor;

    @BeforeEach
    void setUp() {
        placeholderPool = Executors.newSingleThreadExecutor();
        executor = new CodeNodeExecutor(new ExpressionEvaluator(new ObjectMapper()),
                new ObjectMapper(), new SensitiveDataFilter());
    }

    @AfterEach
    void tearDown() {
        executor.close();
        placeholderPool.shutdownNow();
    }

    private WorkflowNode node(String language, String code, int timeoutSec) {
        WorkflowNode node = new WorkflowNode();
        node.setKey("transform");
        node.setType("code");
        node.setName("transform");
        node.setTimeoutSec(timeoutSec);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("language", language);
        config.put("code", code);
        config.put("inputs", Map.of("a", "${nodes.a.output}", "b", "${nodes.b.output}"));
        config.put("timeoutSec", timeoutSec);
        node.setConfig(config);
        return node;
    }

    private NodeContext context(WorkflowNode node, VariableStore variables) {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_code");
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(42L);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_code");
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey(node.getKey());
        nodeRun.setNodeType("code");
        nodeRun.setAttempt(1);
        nodeRun.setStatus("running");

        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(List.of(node));
        return new NodeContext(run, nodeRun, node, def, variables, placeholderPool,
                new AtomicBoolean(), new AtomicBoolean(), false, event -> {
        }, variables);
    }

    private VariableStore variables() {
        VariableStore variables = new VariableStore();
        variables.putNodeOutput("a", null, 2);
        variables.putNodeOutput("b", null, 3);
        return variables;
    }

    @Test
    void publishValidationRejectsOtherwiseValidCodeNode() {
        WorkflowNode node = node("javascript", "function main(input) { return input; }", 5);
        ValidationIssue.Sink issues = new ValidationIssue.Sink();
        executor.validate(node, new WorkflowDefinition(), issues);
        assertThat(issues.issues()).anySatisfy(issue -> {
            assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
            assertThat(issue.code()).isEqualTo("CODE_RUNTIME_UNAVAILABLE");
            assertThat(issue.nodeKey()).isEqualTo("transform");
            assertThat(issue.message()).contains("不可用");
        });
    }

    @Test
    void workflowExecutionFailsClosedWithoutWritingOutput() {
        VariableStore variables = variables();
        WorkflowNode node = node("javascript", "function main(input) { while (true) {} }", 1);
        assertUnavailable(() -> executor.execute(context(node, variables)));
        assertThat(variables.nodeOutput("transform")).isNull();
    }

    @Test
    void sampleFailsClosedEvenForInvalidOrMissingArguments() {
        assertUnavailable(() -> executor.executeSample(node("javascript",
                "function main(input) { while (true) {} }", 1), Map.of()));
        assertUnavailable(() -> executor.executeSample(null, null));
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(NodeFailedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CODE_RUNTIME_UNAVAILABLE");
                    assertThat(e.getMessage()).contains("未执行");
                });
    }
}
