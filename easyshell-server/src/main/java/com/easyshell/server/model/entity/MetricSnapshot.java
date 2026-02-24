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
@Table(name = "metric_snapshot", indexes = {
    @Index(name = "idx_metric_agent_time", columnList = "agent_id, recorded_at")
})
public class MetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "cpu_usage")
    private Double cpuUsage;

    @Column(name = "mem_usage")
    private Double memUsage;

    @Column(name = "disk_usage")
    private Double diskUsage;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;
}
