package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentHeartbeatRequest {

    @NotBlank(message = "agentId is required")
    private String agentId;

    private Double cpuUsage;
    private Double memUsage;
    private Double diskUsage;
}
