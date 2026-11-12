package com.easyshell.server.common.exception;

import com.easyshell.server.model.vo.WorkflowRiskBlockedVO;
import lombok.Getter;

/**
 * 风险门禁拦截（§6.2：449 {@code WORKFLOW_RISK_BLOCKED} / {@code RISK_NEED_APPROVAL}）。
 *
 * <p>响应体<b>只含等级与模式串</b>（B2 #13 / C-9）：刻意不携带 {@code RiskAssessment}，
 * 因为它的 {@code commandRisks} 里每条 {@code CommandRisk} 都带命令原文。</p>
 *
 * <p>HTTP 状态沿用 {@code TaskController.java:70-77} 的既有约定：<b>HTTP 200 + body code 449</b>
 * （449 不是合法 HTTP 状态码，前端按 body code 弹审批确认框）。</p>
 */
@Getter
public class WorkflowRiskBlockedException extends RuntimeException {

    private final WorkflowRiskBlockedVO blocked;

    public WorkflowRiskBlockedException(WorkflowRiskBlockedVO blocked) {
        super(blocked.getCode() + ": " + blocked.getMessage());
        this.blocked = blocked;
    }
}
