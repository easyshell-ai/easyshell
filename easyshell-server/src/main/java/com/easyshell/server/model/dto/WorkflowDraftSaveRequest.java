package com.easyshell.server.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存草稿请求（§6.1 PUT /api/v1/workflow/{id}/versions/draft）。
 *
 * <p><b>乐观锁</b>：{@code baseVersion} 是客户端加载草稿时拿到的并发令牌（=
 * 服务端 {@code workflow_version.base_version} 当前值）。条件 UPDATE 影响 0 行 →
 * 409 VERSION_CONFLICT + 三选一契约（{@link #onConflict}：reject 默认 / overwrite 覆盖保存 /
 * copy 另存副本；放弃重载由前端直接重新拉取）。</p>
 *
 * <p>definition 用 {@link JsonNode} 承载而不是直接绑 {@code WorkflowDefinition}：
 * 实体是 {@code ignoreUnknown=false} 的服务端权威 schema，未知字段必须转成
 * 400 WORKFLOW_PARAM_INVALID，而不是被 Jackson 抛成 500。</p>
 */
@Data
public class WorkflowDraftSaveRequest {

    private JsonNode definition;

    @Size(max = 512)
    private String changelog;

    /** 已加载或确认的目标草稿快照；更新时三项均必填。 */
    private Long draftId;

    private Integer baseVersion;

    private String definitionSha256;

    /** 仅在确认不存在草稿时首次创建，必须显式为 true 且 baseVersion 为 0。 */
    private Boolean expectedAbsent;

    /** reject(默认) / overwrite / copy */
    private String onConflict;
}
