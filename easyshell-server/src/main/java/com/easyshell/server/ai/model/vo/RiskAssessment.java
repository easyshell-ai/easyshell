package com.easyshell.server.ai.model.vo;

import com.easyshell.server.ai.risk.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {
    private RiskLevel overallRisk;
    private List<CommandRisk> commandRisks;
    private List<String> bannedMatches;
    private boolean autoExecutable;
    private String explanation;

    public static RiskAssessment banned(List<String> matches) {
        return RiskAssessment.builder()
                .overallRisk(RiskLevel.BANNED)
                .bannedMatches(matches)
                .commandRisks(Collections.emptyList())
                .autoExecutable(false)
                .explanation("脚本包含封禁命令: " + String.join(", ", matches))
                .build();
    }

    public static RiskAssessment of(RiskLevel maxRisk, List<CommandRisk> risks) {
        return RiskAssessment.builder()
                .overallRisk(maxRisk)
                .commandRisks(risks)
                .bannedMatches(Collections.emptyList())
                .autoExecutable(maxRisk == RiskLevel.LOW)
                .explanation(buildExplanation(maxRisk, risks))
                .build();
    }

    private static String buildExplanation(RiskLevel level, List<CommandRisk> risks) {
        if (risks == null || risks.isEmpty()) return "无命令";
        long nonLowCount = risks.stream().filter(r -> r.getLevel() != RiskLevel.LOW).count();
        return switch (level) {
            case LOW -> "所有命令均为低风险，AI 可自主执行";
            case MEDIUM -> "包含 " + nonLowCount + " 条中风险命令，需人工确认";
            case HIGH -> "包含高危命令，禁止 AI 执行";
            case BANNED -> "包含封禁命令";
        };
    }
}
