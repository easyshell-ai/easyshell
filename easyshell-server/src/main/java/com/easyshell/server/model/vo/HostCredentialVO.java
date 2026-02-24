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
    private String agentId;
    private String provisionStatus;
    private String provisionLog;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
