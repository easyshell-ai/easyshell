package com.easyshell.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 409 VERSION_CONFLICT 的三选一数据契约（§6.1：覆盖保存 + 审计 WORKFLOW_OVERWRITE /
 * 另存副本 / 放弃重载；<b>不做自动三方合并</b>）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowVersionConflictVO {

    private String code;
    private String message;

    /** 服务端当前草稿的 base_version（客户端据此决定放弃重载） */
    private Integer currentBaseVersion;

    private Long draftVersionId;

    /** 仅来自锁内读取的当前草稿；null 表示未提供可信快照。 */
    private String currentDefinitionSha256;

    /** true/false 表示锁内确认存在/不存在；null 表示状态未知。 */
    private Boolean currentDraftExists;

    /** 固定三个选项，顺序即前端弹窗的展示顺序 */
    private List<ConflictOption> options;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConflictOption {
        /** overwrite / copy / reload */
        private String action;
        private String label;
        private String description;
    }

    public static WorkflowVersionConflictVO of(Integer currentBaseVersion, Long draftVersionId) {
        return create(currentBaseVersion, draftVersionId, null, null);
    }

    public static WorkflowVersionConflictVO currentDraft(Integer baseVersion, Long draftId, String sha256) {
        return create(baseVersion, draftId, sha256, true);
    }

    public static WorkflowVersionConflictVO noDraft() {
        return create(null, null, null, false);
    }

    private static WorkflowVersionConflictVO create(Integer currentBaseVersion, Long draftVersionId,
                                                     String sha256, Boolean draftExists) {
        return WorkflowVersionConflictVO.builder()
                .code("VERSION_CONFLICT")
                .message(Boolean.FALSE.equals(draftExists) ? "当前没有草稿，请重新加载后确认首次创建。"
                        : "草稿已更改，请选择：覆盖保存 / 另存副本 / 放弃重载。M1 不做自动合并。")
                .currentBaseVersion(currentBaseVersion)
                .draftVersionId(draftVersionId)
                .currentDefinitionSha256(sha256)
                .currentDraftExists(draftExists)
                .options(List.of(
                        ConflictOption.builder()
                                .action("overwrite")
                                .label("覆盖保存")
                                .description("以你手中的内容覆盖服务端草稿，并记 WORKFLOW_OVERWRITE 审计")
                                .build(),
                        ConflictOption.builder()
                                .action("copy")
                                .label("另存副本")
                                .description("把你的内容存为一个新版本草稿，不动服务端当前草稿")
                                .build(),
                        ConflictOption.builder()
                                .action("reload")
                                .label("放弃重载")
                                .description("丢弃本地修改，重新拉取服务端草稿")
                                .build()))
                .build();
    }
}
