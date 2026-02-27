package com.easyshell.server.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiScheduledTaskRequest {

    @NotBlank(message = "名称不能为空")
    private String name;

    private String description;

    @NotBlank(message = "任务类型不能为空")
    private String taskType;

    @NotBlank(message = "Cron 表达式不能为空")
    private String cronExpression;

    @NotBlank(message = "目标类型不能为空")
    private String targetType;

    @NotBlank(message = "目标 ID 不能为空")
    private String targetIds;

    private String scriptTemplate;

    private String aiPrompt;

    private Boolean enabled = true;

    private String notifyStrategy;

    private String notifyChannels;

    private String notifyAiPrompt;
}
