package com.easyshell.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentBriefVO {

    private String id;
    private String hostname;
    private String ip;
    private Double cpuUsage;
    private Double memUsage;
    private Double diskUsage;
    private LocalDateTime lastHeartbeat;
}
