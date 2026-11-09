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
        return WorkflowVersionConflictVO.builder()
                .code("VERSION_CONFLICT")
                .message("他人在你之后保存过草稿（base_version 已从 " + currentBaseVersion
                        + " 前进一步）。请选择：覆盖保存 / 另存副本 / 放弃重载。M1 不做自动合并。")
                .currentBaseVersion(currentBaseVersion)
                .draftVersionId(draftVersionId)
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
