package com.easyshell.server.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    /** 历史字段（credential.hostName）；显式命名避免与 hostname 在 Jackson 中互相覆盖。 */
    @JsonProperty("hostName")
    private String hostName;

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    private String agentId;
    private String provisionStatus;
    private String provisionLog;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;

    // Agent-merged fields (null when no agent registered yet)
    /** 统一列表字段（agent.hostname）；与 hostName 同时填充，兼容两代前端读取。 */
    @JsonProperty("hostname")
    private String hostname;

    /**
     * Lombok 在 {@code hostName}/{@code hostname} 大小写仅差一个字母时只生成一个访问器，
     * 导致 {@code hostname} 对 Jackson 不可见。这里显式补齐，保证两个 JSON 字段都存在。
     */
    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

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
