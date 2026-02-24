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
@Table(name = "cluster_agent")
@IdClass(ClusterAgentId.class)
public class ClusterAgent {

    @Id
    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    @Id
    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
