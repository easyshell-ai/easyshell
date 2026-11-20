package com.easyshell.server.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "workflow_version", uniqueConstraints = {
    @UniqueConstraint(name = "uk_wv_workflow_version", columnNames = {"workflow_id", "version"})
})
public class WorkflowVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 软关联 workflow.id（零外键） */
    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(nullable = false)
    private Integer version;

    /** 保存草稿时携带的基础版本号，冲突时返回 409 VERSION_CONFLICT */
    @Column(name = "base_version", nullable = false)
    private Integer baseVersion = 0;

    /** 发布后的定义 JSON，本行不可变（§7.2.1 执行指纹要素①的载体） */
    @Lob
    @Column(name = "definition_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String definitionJson;

    /** definition_json 的 SHA-256，replay（M3）与 diff 的比对基准 */
    @Column(name = "definition_sha256", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String definitionSha256;

    @Column(length = 512)
    private String changelog;

    @Column(name = "is_valid", nullable = false)
    private Boolean isValid = false;

    /** LOW / MEDIUM / HIGH / BANNED，插值前评估的估算值（G-16：UI 须标注为估算） */
    @Column(name = "estimated_risk", length = 16)
    private String estimatedRisk;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;
}
