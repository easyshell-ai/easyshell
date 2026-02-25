package com.easyshell.server.ai.model.entity;

import com.easyshell.server.model.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_scheduled_task")
public class AiScheduledTask extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /**
     * inspect, detect, custom
     */
    @Column(name = "task_type", nullable = false, length = 32)
    private String taskType;

    @Column(name = "cron_expression", nullable = false, length = 64)
    private String cronExpression;

    /**
     * agent, cluster, tag
     */
    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    /**
     * Comma-separated target IDs
     */
    @Column(name = "target_ids", nullable = false, length = 1024)
    private String targetIds;

    @Lob
    @Column(name = "script_template", columnDefinition = "TEXT")
    private String scriptTemplate;

    @Lob
    @Column(name = "ai_prompt", columnDefinition = "TEXT")
    private String aiPrompt;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 通知策略: none / always / on_alert / on_failure
     */
    @Column(name = "notify_strategy", length = 32)
    private String notifyStrategy = "none";

    /**
     * 通知渠道（逗号分隔），如 "telegram,discord"
     */
    @Column(name = "notify_channels", length = 256)
    private String notifyChannels;
}
