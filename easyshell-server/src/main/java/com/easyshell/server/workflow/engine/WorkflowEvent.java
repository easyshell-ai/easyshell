package com.easyshell.server.workflow.engine;

import java.time.Instant;

/**
 * 工作流事件（WS 上线前的统一出口；M1 只做脱敏日志，§6.7.1 出口②）。
 *
 * @param type     RUN_STARTED / NODE_STARTED / NODE_SUCCEEDED / NODE_FAILED / NODE_SKIPPED /
 *                 NODE_WAITING / NODE_CANCELLED / APPROVAL_REQUESTED / APPROVAL_DECIDED /
 *                 RUN_WAITING / RUN_SUCCEEDED / RUN_FAILED / RUN_CANCELLED / RUN_TIMEOUT
 * @param runId    run ID
 * @param nodeKey  关联节点（run 级事件为 null）
 * @param message  人读信息（已过 SensitiveDataFilter）
 * @param payload  结构化负载（M1 不落日志，避免把输出写进出站通道）
 * @param at       事件时刻
 */
public record WorkflowEvent(String type, String runId, String nodeKey, String message, Object payload,
                            Instant at) {

    public static WorkflowEvent of(String type, String runId, String nodeKey, String message) {
        return new WorkflowEvent(type, runId, nodeKey, message, null, Instant.now());
    }
}
