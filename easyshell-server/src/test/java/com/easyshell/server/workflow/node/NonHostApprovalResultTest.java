package com.easyshell.server.workflow.node;

import com.easyshell.server.model.entity.WorkflowApproval;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NonHostApprovalResultTest {
    @Test
    void resultNeverContainsDecisionComment() {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setStatus("approved");
        approval.setDecidedBy(7L);
        approval.setDecisionComment("private-comment-marker");

        var output = NonHostApprovalResult.output(approval);
        assertEquals("approved", output.get("decision"));
        assertEquals(7L, output.get("decidedBy"));
        assertFalse(output.containsKey("comment"));
        assertFalse(output.toString().contains("private-comment-marker"));
        assertFalse(NonHostApprovalResult.rejectionMessage().contains("private-comment-marker"));
    }

    @Test
    void nullDeciderIsPreservedWithoutAddingComment() {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setStatus("timeout");
        var output = NonHostApprovalResult.output(approval);
        assertTrue(output.containsKey("decidedBy"));
        assertNull(output.get("decidedBy"));
        assertFalse(output.containsKey("comment"));
    }
}
