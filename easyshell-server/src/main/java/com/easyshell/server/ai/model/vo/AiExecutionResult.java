package com.easyshell.server.ai.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiExecutionResult {
    private String status;  // "executed", "pending_approval", "rejected"
    private String taskId;
    private String message;
    private List<CommandRisk> commandRisks;
    private RiskAssessment riskAssessment;

    public static AiExecutionResult executed(String taskId) {
        return AiExecutionResult.builder()
                .status("executed")
                .taskId(taskId)
                .message("AI 已自主执行低风险脚本")
                .build();
    }

    public static AiExecutionResult pendingApproval(String taskId, String message, List<CommandRisk> risks) {
        return AiExecutionResult.builder()
                .status("pending_approval")
                .taskId(taskId)
                .message(message)
                .commandRisks(risks)
                .build();
    }

    public static AiExecutionResult rejected(String message) {
        return AiExecutionResult.builder()
                .status("rejected")
                .message(message)
                .build();
    }

    public boolean isExecuted() {
        return "executed".equals(status);
    }

    public boolean isPendingApproval() {
        return "pending_approval".equals(status);
    }

    public boolean isRejected() {
        return "rejected".equals(status);
    }
}
