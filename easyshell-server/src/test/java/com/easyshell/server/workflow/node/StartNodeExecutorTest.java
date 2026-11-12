package com.easyshell.server.workflow.node;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.model.VariableSpec;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("start 节点 — 把声明入参读成节点输出")
class StartNodeExecutorTest {

    private final StartNodeExecutor executor = new StartNodeExecutor();

    private WorkflowNode node(String key) {
        WorkflowNode node = new WorkflowNode();
        node.setKey(key);
        node.setType("start");
        node.setName(key);
        node.setConfig(Map.of());
        return node;
    }

    private NodeContext context(WorkflowNode node, WorkflowDefinition def, VariableStore variables) {
        WorkflowRun run = new WorkflowRun();
        run.setId("wfr_start");
        run.setWorkflowId(1L);
        run.setVersionId(1L);
        run.setVersionNo(1);
        run.setStatus("running");
        run.setStartedBy(42L);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId("wnr_start");
        nodeRun.setRunId(run.getId());
        nodeRun.setNodeKey(node.getKey());
        nodeRun.setNodeType("start");
        nodeRun.setAttempt(1);
        nodeRun.setStatus("running");

        return new NodeContext(run, nodeRun, node, def, variables,
                Executors.newFixedThreadPool(1), new AtomicBoolean(), new AtomicBoolean(),
                false, event -> {
        }, variables);
    }

    @Test
    @DisplayName("只输出存在值的声明入参；缺失键跳过")
    void buildsOutputFromDeclaredVariables() {
        WorkflowNode start = node("begin");
        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(List.of(start));
        VariableSpec threshold = new VariableSpec();
        threshold.setType("number");
        VariableSpec host = new VariableSpec();
        host.setType("string");
        VariableSpec optional = new VariableSpec();
        optional.setType("string");
        def.getVariables().put("threshold", threshold);
        def.getVariables().put("host", host);
        def.getVariables().put("optional", optional);

        VariableStore variables = new VariableStore();
        variables.putInput("threshold", 93.5);
        variables.putInput("host", "web-1");

        assertThat(executor.execute(context(start, def, variables))).isEqualTo(NodeOutcome.SUCCEEDED);

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) variables.nodeOutput("begin").value();
        assertThat(output).containsExactlyInAnyOrderEntriesOf(Map.of("threshold", 93.5, "host", "web-1"));
        assertThat(output).doesNotContainKey("optional");
    }

    @Test
    @DisplayName("没有声明入参：输出空 map")
    void noVariablesOutputsEmptyMap() {
        WorkflowNode start = node("begin");
        WorkflowDefinition def = new WorkflowDefinition();
        def.setNodes(List.of(start));

        VariableStore variables = new VariableStore();
        executor.execute(context(start, def, variables));

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) variables.nodeOutput("begin").value();
        assertThat(output).isEmpty();
    }
}
