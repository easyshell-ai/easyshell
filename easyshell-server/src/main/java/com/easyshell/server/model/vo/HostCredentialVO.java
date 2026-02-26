package com.easyshell.server.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HostCredentialVO {
    private Long id;
    private String ip;
    private Integer sshPort;
    private String sshUsername;
    private String authType;
    private String hostName;
    private String agentId;
    private String provisionStatus;
    private String provisionLog;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;

    // Agent-merged fields (null when no agent registered yet)
    private String hostname;
    private String os;
    private String arch;
    private String kernel;
    private String cpuModel;
    private Integer cpuCores;
    private Long memTotal;
    private String agentVersion;
    private Integer agentStatus;
    private String lastHeartbeat;
    private Double cpuUsage;
    private Double memUsage;
    private Double diskUsage;
    private String registeredAt;
}
