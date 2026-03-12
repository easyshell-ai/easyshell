package com.easyshell.server.ai.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AiExecutionRequest {
    @NotBlank
    private String scriptContent;
    private String description;
    private List<String> agentIds;
    private Integer timeoutSeconds;
    private Long userId;
    private String sourceIp;
    private Map<String, String> parameters;
}
