package com.easyshell.server.model.vo;

import com.easyshell.server.ai.model.vo.CommandRisk;
import com.easyshell.server.ai.model.vo.RiskAssessment;
import com.easyshell.server.ai.risk.RiskLevel;
import lombok.Getter;

import java.util.List;

/** Safe outbound projection for a task creation 449 response. */
@Getter
public final class TaskRiskAssessmentVO {

    private final RiskLevel overallRisk;
    private final List<String> bannedMatches;
    private final String explanation;

    private TaskRiskAssessmentVO(RiskAssessment risk) {
        this.overallRisk = risk.getOverallRisk();
        // A configured literal pattern can be a secret or an entire command; even an
        // internal pattern may equal the command. Preserve the field, not its contents.
        this.bannedMatches = List.of();
        this.explanation = buildExplanation(risk);
    }

    public static TaskRiskAssessmentVO from(RiskAssessment risk) {
        return new TaskRiskAssessmentVO(risk);
    }

    private static String buildExplanation(RiskAssessment risk) {
        RiskLevel level = risk.getOverallRisk();
        if (level == null) {
            return "需人工确认";
        }
        return switch (level) {
            case LOW -> "所有命令均为低风险，AI 可自主执行";
            case MEDIUM -> {
                List<CommandRisk> risks = risk.getCommandRisks();
                if (risks == null || risks.isEmpty()) {
                    yield "无命令";
                }
                long nonLowCount = risks.stream()
                        .filter(commandRisk -> commandRisk != null && commandRisk.getLevel() != RiskLevel.LOW)
                        .count();
                yield "包含 " + nonLowCount + " 条中风险命令，需人工确认";
            }
            case HIGH -> "包含高危命令，需管理员审批后执行";
            case BANNED -> "包含封禁命令，需管理员审批后执行";
        };
    }
}
