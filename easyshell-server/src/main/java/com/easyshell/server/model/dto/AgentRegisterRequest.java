package com.easyshell.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentRegisterRequest {

    @NotBlank(message = "agentId is required")
    private String agentId;

    @NotBlank(message = "hostname is required")
    private String hostname;

    @NotBlank(message = "ip is required")
    private String ip;

    private String os;
    private String arch;
    private String kernel;
    private String cpuModel;
    private Integer cpuCores;
    private Long memTotal;
    private String agentVersion;
}
