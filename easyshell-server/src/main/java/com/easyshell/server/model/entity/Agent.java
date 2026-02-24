package com.easyshell.server.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "agent")
public class Agent extends BaseEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String hostname;

    @Column(nullable = false, length = 64)
    private String ip;

    @Column(length = 64)
    private String os;

    @Column(length = 32)
    private String arch;

    @Column(length = 128)
    private String kernel;

    @Column(name = "cpu_model", length = 128)
    private String cpuModel;

    @Column(name = "cpu_cores")
    private Integer cpuCores;

    @Column(name = "mem_total")
    private Long memTotal;

    @Column(name = "agent_version", length = 32)
    private String agentVersion;

    /**
     * 0: offline, 1: online, 2: unstable
     */
    @Column(nullable = false)
    private Integer status = 0;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "cpu_usage")
    private Double cpuUsage;

    @Column(name = "mem_usage")
    private Double memUsage;

    @Column(name = "disk_usage")
    private Double diskUsage;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;
}
