package com.easyshell.server.workflow.node;

import com.easyshell.server.model.entity.WorkflowApproval;
import com.easyshell.server.model.entity.WorkflowNodeRun;
import com.easyshell.server.model.entity.WorkflowRun;
import com.easyshell.server.workflow.approval.WorkflowApprovalStore;
import com.easyshell.server.workflow.approval.WorkflowApprovalSuspensionService;
import com.easyshell.server.workflow.engine.NodeContext;
import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.engine.NodeOutcome;
import com.easyshell.server.workflow.engine.VariableStore;
import com.easyshell.server.workflow.expression.ExpressionEvaluator;
import com.easyshell.server.workflow.model.WorkflowNode;
import com.easyshell.server.workflow.properties.WorkflowProperties;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService;
import com.easyshell.server.workflow.service.WorkflowRunProvenanceService.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovalExecutionIsolationTest {
    private final WorkflowApprovalStore store = mock(WorkflowApprovalStore.class);
    private final WorkflowApprovalSuspensionService suspension = mock(WorkflowApprovalSuspensionService.class);
    private final WorkflowRunProvenanceService provenance = mock(WorkflowRunProvenanceService.class);
    private final ApprovalNodeExecutor executor = new ApprovalNodeExecutor(store, suspension,
            mock(ExpressionEvaluator.class), new WorkflowProperties(), provenance);
    private final NodeContext context = mock(NodeContext.class);
    private final WorkflowNode node = mock(WorkflowNode.class);
    private final VariableStore variables = mock(VariableStore.class);

    private void setup(String status, VerifiedIdentity identity) {
        WorkflowRun run = new WorkflowRun();
        run.setId("run");
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setApprovalId(1L);
        nodeRun.setNodeType("approval");
        WorkflowApproval approval = new WorkflowApproval();
        approval.setRunId("run");
        approval.setNodeKey("node");
        approval.setNodeType("approval");
        approval.setStatus(status);
        approval.setDecisionComment("private-comment-marker");
        when(context.run()).thenReturn(run);
        when(context.nodeRun()).thenReturn(nodeRun);
        when(context.node()).thenReturn(node);
        when(context.variables()).thenReturn(variables);
        when(node.getKey()).thenReturn("node");
        when(store.findById(1L)).thenReturn(Optional.of(approval));
        when(provenance.verifiedIdentity("run")).thenReturn(identity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approved", "timeout"})
    void v3SuccessfulDecisionsExcludeComment(String status) {
        setup(status, VerifiedIdentity.V3);
        when(node.configString("onTimeout", "reject")).thenReturn("approve");
        assertEquals(NodeOutcome.SUCCEEDED, executor.execute(context));
        verify(variables).putNodeOutput(eq("node"), any(), argThat(value ->
                value instanceof Map<?, ?> map && !map.containsKey("comment")
                        && !map.toString().contains("private-comment-marker")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rejected", "timeout", "cancelled", "pending"})
    void v3FailureDecisionsExcludeComment(String status) {
        setup(status, VerifiedIdentity.V3);
        when(node.configString("onTimeout", "reject")).thenReturn("reject");
        NodeFailedException error = assertThrows(NodeFailedException.class, () -> executor.execute(context));
        assertFalse(error.getMessage().contains("private-comment-marker"));
        verifyNoInteractions(variables);
    }

    @Test
    void legacyRetainsComment() {
        setup("approved", VerifiedIdentity.LEGACY);
        executor.execute(context);
        verify(variables).putNodeOutput(eq("node"), any(), argThat(value ->
                value instanceof Map<?, ?> map && "private-comment-marker".equals(map.get("comment"))));
    }

    @Test
    void legacyRejectionRetainsComment() {
        setup("rejected", VerifiedIdentity.LEGACY);
        assertTrue(assertThrows(NodeFailedException.class, () -> executor.execute(context))
                .getMessage().contains("private-comment-marker"));
    }

    @Test
    void unknownAndLookupFailureCannotReadOrCreateApproval() {
        setup("approved", VerifiedIdentity.UNKNOWN);
        assertEquals("APPROVAL_PROVENANCE_UNKNOWN", assertThrows(NodeFailedException.class,
                () -> executor.execute(context)).getCode());
        when(provenance.verifiedIdentity("run")).thenThrow(new IllegalStateException("private-comment-marker"));
        NodeFailedException error = assertThrows(NodeFailedException.class, () -> executor.execute(context));
        assertFalse(error.getMessage().contains("private-comment-marker"));
        verifyNoInteractions(store, suspension, variables);
    }

    @Test
    void unknownCannotSuspendNewApproval() {
        WorkflowRun run = new WorkflowRun();
        run.setId("run");
        when(context.run()).thenReturn(run);
        when(context.node()).thenReturn(node);
        when(provenance.verifiedIdentity("run")).thenReturn(null);
        assertEquals("APPROVAL_PROVENANCE_UNKNOWN", assertThrows(NodeFailedException.class,
                () -> executor.execute(context)).getCode());
        verifyNoInteractions(store, suspension, variables);
    }
}
