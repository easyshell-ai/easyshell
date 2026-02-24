package com.easyshell.server.ai.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AiInspectReportVO {

    private Long id;
    private Long scheduledTaskId;
    private String taskType;
    private String taskName;
    private String targetSummary;
    private String scriptOutput;
    private String aiAnalysis;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
}
