package com.easyshell.server.model.dto;

import lombok.Data;

/**
 * 发布请求（§6.1 POST /api/v1/workflow/{id}/publish）。
 *
 * <p>发布的是<b>当前草稿行</b>（version 最大的未发布版本）；changelog 缺省时沿用
 * 草稿上保存的那一份。</p>
 */
@Data
public class WorkflowPublishRequest {

    private String changelog;
}
