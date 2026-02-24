package com.easyshell.server.ai.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiAlertAnalysis {

    private String analysis;
    private String suggestedAction;
    private String riskLevel;
    private boolean autoFixAvailable;
    private String autoFixScript;
}
