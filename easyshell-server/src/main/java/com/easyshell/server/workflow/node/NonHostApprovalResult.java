package com.easyshell.server.workflow.node;

import com.easyshell.server.model.entity.WorkflowApproval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Result construction for an independently verified NON_HOST_V3 run only. */
public final class NonHostApprovalResult {
    private NonHostApprovalResult() { }

    /** Never include the approval comment, even when it is null or empty. */
    public static Map<String, Object> output(WorkflowApproval approval) {
        Objects.requireNonNull(approval, "approval");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("decision", approval.getStatus());
        output.put("decidedBy", approval.getDecidedBy());
        return output;
    }

    /** Rejection errors are observable by run readers and must not contain the comment. */
    public static String rejectionMessage() {
        return "审批被拒绝";
    }
}
