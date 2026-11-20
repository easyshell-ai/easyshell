package com.easyshell.server.common.exception;

import com.easyshell.server.model.vo.WorkflowValidateResultVO;
import lombok.Getter;

/**
 * 发布校验未通过（§6.1 POST /{id}/publish：存在 ERROR 级 issue）。
 *
 * <p>HTTP 422 + {@code issues[]} 结构化响应体（前端 issues 面板直接渲染）。
 * 只带等级/错误码/人读信息，<b>不含脚本原文与参数值</b>。</p>
 */
@Getter
public class WorkflowValidationFailedException extends RuntimeException {

    private final WorkflowValidateResultVO result;

    public WorkflowValidationFailedException(WorkflowValidateResultVO result) {
        super("WORKFLOW_VALIDATION_FAILED: 发布校验存在 "
                + result.getIssues().stream()
                .filter(i -> "ERROR".equals(i.getSeverity())).count() + " 个 ERROR");
        this.result = result;
    }
}
