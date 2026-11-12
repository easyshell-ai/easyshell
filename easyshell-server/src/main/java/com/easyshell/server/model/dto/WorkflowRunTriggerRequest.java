package com.easyshell.server.model.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 触发运行请求（§6.1 POST /api/v1/workflow/{id}/runs，MF-5 唯一触发路径）。
 *
 * <p>{@code POST /runs}（body 带 workflowId 的重复触发路径）按 N7-d <b>永久删除</b>——
 * 它只挂 requireRunAccess，是越权触发的新洞口。</p>
 */
@Data
public class WorkflowRunTriggerRequest {

    /** 缺省 = workflow.current_version_id */
    private Long versionId;

    /** run 级入参（按 definition.variables 校验，未知字段一律拒绝） */
    private Map<String, Object> input = new LinkedHashMap<>();

    /** 幂等键：同 userId 同 key 重复触发返回首个 run（§3.5 幂等①） */
    private String idempotencyKey;
}
