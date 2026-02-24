package com.easyshell.server.ai.model.dto;

import lombok.Data;

@Data
public class AiAlertRequest {

    private String alertDescription;

    private String agentId;

    private String alertSource;

    private String severity;
}
