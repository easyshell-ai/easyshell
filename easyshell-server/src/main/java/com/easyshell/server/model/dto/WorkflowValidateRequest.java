package com.easyshell.server.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 校验请求（§6.1 POST /api/v1/workflow/{id}/validate）。
 *
 * <p>definition 用 {@link JsonNode} 承载，理由同 {@link WorkflowDraftSaveRequest}。</p>
 */
@Data
public class WorkflowValidateRequest {

    private JsonNode definition;
}
