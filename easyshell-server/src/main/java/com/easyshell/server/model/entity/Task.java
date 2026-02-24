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
@Table(name = "task")
public class Task extends BaseEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "script_id")
    private Long scriptId;

    @Lob
    @Column(name = "script_content", columnDefinition = "MEDIUMTEXT")
    private String scriptContent;

    @Column(name = "script_type", length = 32)
    private String scriptType = "shell";

    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds = 3600;

    /**
     * 0: pending, 1: running, 2: success, 3: partial_success, 4: failed, 5: cancelled, 6: pending_approval
     */
    @Column(nullable = false)
    private Integer status = 0;

    @Column(name = "total_count")
    private Integer totalCount = 0;

    @Column(name = "success_count")
    private Integer successCount = 0;

    @Column(name = "failed_count")
    private Integer failedCount = 0;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "source", length = 32)
    private String source = "user";

    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    @Column(name = "approval_status", length = 16)
    private String approvalStatus;

    @Column(name = "target_agent_ids", length = 2000)
    private String targetAgentIds;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
