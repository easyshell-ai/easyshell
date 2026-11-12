package com.easyshell.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 449 风险门禁响应体（§6.2 WORKFLOW_RISK_BLOCKED / RISK_NEED_APPROVAL）。
 *
 * <p><b>硬规则（B2 #13 / C-9）</b>：只允许存<b>等级与模式串</b>，禁止存命令原文——
 * 不引用 {@code RiskAssessment.commandRisks}（{@code CommandRisk} 携带命令原文）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRiskBlockedVO {

    private String code;
    private String message;
    private String riskLevel;
    private List<String> riskMatches;
    private String riskPolicy;
    private String nodeKey;
}
