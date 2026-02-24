package com.easyshell.server.ai.agent;

import lombok.Data;

import java.time.Instant;

@Data
public class BackgroundTask {

    private String taskId;
    private String agentName;
    private String status;  // pending | running | completed | failed
    private String result;
    private String error;
    private Instant createdAt;
    private Instant completedAt;

    private String hostId;
    private Integer stepIndex;
    private Instant startedAt;
    private Long durationMs;

    public BackgroundTask(String taskId, String agentName) {
        this.taskId = taskId;
        this.agentName = agentName;
        this.status = "pending";
        this.createdAt = Instant.now();
    }
}
