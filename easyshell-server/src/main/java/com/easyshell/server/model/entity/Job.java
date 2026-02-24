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
@Table(name = "job")
public class Job extends BaseEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    /**
     * 0: pending, 1: running, 2: success, 3: failed, 4: timeout, 5: cancelled
     */
    @Column(nullable = false)
    private Integer status = 0;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String output;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
