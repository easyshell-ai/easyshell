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
@Table(name = "workflow", indexes = {
    @Index(name = "idx_wf_category", columnList = "category"),
    @Index(name = "idx_wf_status_visibility", columnList = "status, visibility"),
    @Index(name = "idx_wf_created_by", columnList = "created_by")
})
public class Workflow extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(length = 50)
    private String category;

    /**
     * draft: 草稿, published: 已发布, archived: 已归档（软删，G-18）
     */
    @Column(nullable = false, length = 16)
    private String status = "draft";

    /**
     * private: 仅创建者可见, public: 全员可见
     */
    @Column(name = "visibility", nullable = false, length = 16)
    private String visibility = "private";

    @Column(length = 32)
    private String source = "user";

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 软关联 workflow_version.id（零外键） */
    @Column(name = "current_version_id")
    private Long currentVersionId;

    @Column(name = "run_count", nullable = false)
    private Integer runCount = 0;

    @Column(name = "success_count", nullable = false)
    private Integer successCount = 0;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;
}
