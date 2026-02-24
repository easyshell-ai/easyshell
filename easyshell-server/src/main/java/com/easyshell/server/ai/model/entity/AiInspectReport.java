package com.easyshell.server.ai.model.entity;

import com.easyshell.server.model.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_inspect_report", indexes = {
        @Index(name = "idx_air_scheduled_task_id", columnList = "scheduled_task_id")
})
public class AiInspectReport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scheduled_task_id")
    private Long scheduledTaskId;

    @Column(name = "task_type", length = 32)
    private String taskType;

    @Column(name = "task_name", length = 128)
    private String taskName;

    @Column(name = "target_summary", length = 512)
    private String targetSummary;

    @Lob
    @Column(name = "script_output", columnDefinition = "MEDIUMTEXT")
    private String scriptOutput;

    @Lob
    @Column(name = "ai_analysis", columnDefinition = "MEDIUMTEXT")
    private String aiAnalysis;

    /**
     * success, failed, partial
     */
    @Column(length = 32)
    private String status;

    @Column(name = "created_by")
    private Long createdBy;
}
