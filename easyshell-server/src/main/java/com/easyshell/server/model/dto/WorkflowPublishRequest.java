package com.easyshell.server.model.dto;

import lombok.Data;

/**
 * 发布请求（§6.1 POST /api/v1/workflow/{id}/publish）。
 *
 * <p>必须绑定预校验的草稿令牌；旧客户端缺少令牌时返回冲突。</p>
 */
@Data
public class WorkflowPublishRequest {

    private String changelog;
    private Long draftId;
    private Integer baseVersion;
    private String definitionSha256;
}
