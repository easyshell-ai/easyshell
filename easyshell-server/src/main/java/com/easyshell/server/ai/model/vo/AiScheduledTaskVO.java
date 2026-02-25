package com.easyshell.server.ai.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AiScheduledTaskVO {

    private Long id;
    private String name;
    private String description;
    private String taskType;
    private String cronExpression;
    private String targetType;
    private String targetIds;
    private String scriptTemplate;
    private String aiPrompt;
    private Boolean enabled;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextRunAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String notifyStrategy;
    private String notifyChannels;
}
