package com.easyshell.server.workflow.node;

import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConditionNodeExecutorTest {
    @Test
    void evaluationFailureDoesNotProduceFalseOutput() {
        ExpressionEvaluator evaluator = mock(ExpressionEvaluator.class);
        NodeContext context = mock(NodeContext.class);
        WorkflowNode node = new WorkflowNode();
        node.setKey("condition");
        node.setConfig(Map.of("expression", "broken"));
        VariableStore store = new VariableStore();
        when(context.node()).thenReturn(node);
        when(context.variables()).thenReturn(store);
        when(evaluator.evaluate("broken", context.resolver())).thenThrow(new IllegalArgumentException("bad"));
        assertThatThrownBy(() -> new ConditionNodeExecutor(evaluator).execute(context))
                .isInstanceOf(NodeFailedException.class)
                .extracting(e -> ((NodeFailedException) e).getCode()).isEqualTo("CONDITION_EVALUATION_FAILED");
        assertThat(store.nodeOutput("condition")).isNull();
    }

    @Test
    void falseIsAValidSuccessfulResult() {
        WorkflowNode node = new WorkflowNode();
        node.setKey("condition");
        node.setConfig(Map.of("expression", "false"));
        VariableStore store = new VariableStore();
        NodeContext context = mock(NodeContext.class);
        when(context.node()).thenReturn(node);
        when(context.variables()).thenReturn(store);
        when(context.resolver()).thenReturn(store);
        assertThat(new ConditionNodeExecutor(new ExpressionEvaluator(new ObjectMapper())).execute(context))
                .isEqualTo(NodeOutcome.SUCCEEDED);
        assertThat(store.nodeOutput("condition").value()).isEqualTo(Map.of("result", false));
    }
}
