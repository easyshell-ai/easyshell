package com.easyshell.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 校验结果（§6.1 POST /{id}/validate：issues[] + 插值前风险估算）。
 *
 * <p>estimatedRisk/riskMatches 是<b>插值前</b>评估值（G-16：UI 必须标注"估算值，
 * 实际以派发前评估为准"）；riskMatches 只存模式串，绝不存命令原文（C-9）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowValidateResultVO {

    private Boolean valid;
    private List<ValidationIssue> issues;
    private String estimatedRisk;
    private List<String> riskMatches;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationIssue {
        private String severity;
        private String code;
        private String message;
        private String nodeKey;

        public static ValidationIssue from(com.easyshell.server.workflow.graph.ValidationIssue issue) {
            return ValidationIssue.builder()
                    .severity(issue.severity().name())
                    .code(issue.code())
                    .message(issue.message())
                    .nodeKey(issue.nodeKey())
                    .build();
        }
    }
}
