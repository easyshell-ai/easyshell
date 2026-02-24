package com.easyshell.server.ai.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiTestResult {

    private boolean success;
    private String message;
    private long responseTimeMs;
    private String modelInfo;
}
