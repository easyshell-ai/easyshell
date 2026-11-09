package com.easyshell.server.common.exception;

import com.easyshell.server.model.vo.WorkflowVersionConflictVO;
import lombok.Getter;

/**
 * 草稿保存的乐观锁冲突（§6.1：base_version 条件 UPDATE 影响 0 行）。
 *
 * <p>携带 409 的<b>三选一数据契约</b>（覆盖保存 / 另存副本 / 放弃重载），
 * 由 {@code GlobalExceptionHandler} 转成 HTTP 409 + 该契约响应体。
 * M1 明确<b>不做自动三方合并</b>——合并策略必须是用户的显式动作。</p>
 */
@Getter
public class WorkflowVersionConflictException extends RuntimeException {

    private final WorkflowVersionConflictVO conflict;

    public WorkflowVersionConflictException(WorkflowVersionConflictVO conflict) {
        super(conflict.getMessage());
        this.conflict = conflict;
    }
}
