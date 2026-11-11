package com.easyshell.server.workflow.engine;

import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.workflow.model.WorkflowDefinition;
import com.easyshell.server.workflow.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DagSchedulerExternalRetryTest {
    @Test
    void timedOutExternalEffectsAreNeverAutomaticallyRetried() {
        for (String type : List.of("script-exec", "notify", "code")) {
            WorkflowNode node = new WorkflowNode();
            node.setKey("external");
            node.setType(type);
            node.setRetryMax(3);
            WorkflowNodeRun attempt = new WorkflowNodeRun();
            attempt.setNodeKey("external");
            attempt.setAttempt(1);
            attempt.setStatus("timeout");
            WorkflowDefinition definition = new WorkflowDefinition();
            definition.setNodes(List.of(node));
            definition.setEdges(List.of());
            Map<String, WorkflowNodeRun> latest = Map.of("external", attempt);

            assertThat(DagScheduler.automaticRetryAllowed(node)).as(type).isFalse();
            assertThat(DagScheduler.hasRetryRemaining(node, attempt)).as(type).isFalse();
            assertThat(DagScheduler.hasPendingRetry(definition, latest)).as(type).isFalse();
            assertThat(DagScheduler.findReady(definition, latest, ignored -> true)).as(type).isEmpty();
        }
    }
}
