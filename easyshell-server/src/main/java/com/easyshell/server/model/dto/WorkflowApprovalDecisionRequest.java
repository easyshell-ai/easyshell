package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 审批决策请求（§6.1 POST /api/v1/workflow/approvals/{approvalId}/decision）。
 */
@Data
public class WorkflowApprovalDecisionRequest {

    /** approved / rejected */
    @NotBlank
    private String decision;

    private String comment;
}
