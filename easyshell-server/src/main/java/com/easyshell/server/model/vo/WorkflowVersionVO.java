package com.easyshell.server.model.vo;

import com.easyshell.server.model.entity.WorkflowVersion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 版本摘要（§6.1 GET /{id}/versions）——不含 definitionJson（可能很大，按需取
 * {@code /{id}/versions/{versionId}}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowVersionVO {

    private Long id;
    private Integer version;

    /** 草稿并发令牌：保存草稿时原样带回，冲突返回 409 */
    private Integer baseVersion;

    private Boolean isValid;
    private String estimatedRisk;
    private String changelog;
    private String definitionSha256;
    private LocalDateTime publishedAt;
    private Long publishedBy;
    private LocalDateTime createdAt;

    public static WorkflowVersionVO from(WorkflowVersion version) {
        return WorkflowVersionVO.builder()
                .id(version.getId())
                .version(version.getVersion())
                .baseVersion(version.getBaseVersion())
                .isValid(version.getIsValid())
                .estimatedRisk(version.getEstimatedRisk())
                .changelog(version.getChangelog())
                .definitionSha256(version.getDefinitionSha256())
                .publishedAt(version.getPublishedAt())
                .publishedBy(version.getPublishedBy())
                .createdAt(version.getCreatedAt())
                .build();
    }
}
