package com.easyshell.server.workflow.node;

import com.easyshell.server.ai.security.SensitiveDataFilter;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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

@DisplayName("code 节点 — GraalVM 沙箱 JS/Python、超时、无宿主访问")
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> output(VariableStore variables) {
        return (Map<String, Object>) variables.nodeOutput("transform").value();
    }

    @Test
    @DisplayName("JavaScript：main(input) 读取 typed 输入并返回 JSON 对象")
    void javascriptReturnsJson() {
        String code = """
                function main(input) {
                  return { total: input.a + input.b, ok: true, tags: ["x"] };
                }
                """;
        VariableStore variables = variables();
        assertThat(executor.execute(context(node("javascript", code, 5), variables)))
                .isEqualTo(NodeOutcome.SUCCEEDED);

        Map<String, Object> output = output(variables);
        assertThat(((Number) output.get("total")).intValue()).isEqualTo(5);
        assertThat(output.get("ok")).isEqualTo(true);
        assertThat(output.get("tags")).isEqualTo(List.of("x"));
    }

    @Test
    @DisplayName("Python：def main(input) 读取 typed 输入并返回 JSON 对象（运行时可用时）")
    void pythonReturnsJsonIfRuntimeAvailable() {
        String code = """
                def main(input):
                    return {"total": input["a"] + input["b"], "ok": True, "tags": ["x"]}
                """;
        VariableStore variables = variables();
        try {
            executor.execute(context(node("python", code, 15), variables));
        } catch (NodeFailedException e) {
            if ("CODE_RUNTIME_UNAVAILABLE".equals(e.getCode())) {
                Assumptions.abort("GraalPython 运行时不可用：" + e.getMessage());
            }
            throw e;
        }

        Map<String, Object> output = output(variables);
        assertThat(((Number) output.get("total")).intValue()).isEqualTo(5);
        assertThat(output.get("ok")).isEqualTo(true);
    }

    @Test
    @DisplayName("超时：context.close(true) 取消死循环，CODE_TIMEOUT")
    void timeoutCancelsRunawayCode() {
        String code = "function main(input) { while (true) {} }";
        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> executor.execute(context(node("javascript", code, 1), variables())))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("CODE_TIMEOUT"));
        assertThat(System.currentTimeMillis() - started).isLessThan(6000);
    }

    @Test
    @DisplayName("无宿主访问：Java.type 被 HostAccess.NONE 拒绝，CODE_EXECUTION_FAILED")
    void javaTypeIsBlocked() {
        String code = """
                function main(input) {
                  return Java.type("java.lang.System").getProperty("user.home");
                }
                """;
        assertThatThrownBy(() -> executor.execute(context(node("javascript", code, 5), variables())))
                .isInstanceOfSatisfying(NodeFailedException.class, e -> {
                    assertThat(e.getCode()).isIn("CODE_EXECUTION_FAILED", "CODE_RUNTIME_UNAVAILABLE");
                    assertThat(e.getMessage()).doesNotContain(System.getProperty("user.home"));
                });
    }

    @Test
    @DisplayName("缺少 main 入口：CODE_ENTRYPOINT_MISSING")
    void missingEntrypointFails() {
        assertThatThrownBy(() -> executor.execute(
                context(node("javascript", "const x = 1;", 5), variables())))
                .isInstanceOfSatisfying(NodeFailedException.class, e ->
                        assertThat(e.getCode()).isEqualTo("CODE_ENTRYPOINT_MISSING"));
    }
}
