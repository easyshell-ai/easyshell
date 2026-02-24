package com.easyshell.server.ai.model.entity;

import com.easyshell.server.model.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "host_software_inventory", indexes = {
        @Index(name = "idx_hsi_agent_id", columnList = "agent_id"),
        @Index(name = "idx_hsi_software_name", columnList = "software_name")
})
public class HostSoftwareInventory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "software_name", nullable = false, length = 128)
    private String softwareName;

    @Column(name = "software_version", length = 256)
    private String softwareVersion;

    @Column(name = "software_type", nullable = false, length = 32)
    private String softwareType;

    @Column(name = "process_id")
    private Integer processId;

    @Column(name = "listening_ports", length = 512)
    private String listeningPorts;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "is_docker_container", nullable = false)
    private Boolean isDockerContainer = false;

    @Column(name = "docker_image", length = 512)
    private String dockerImage;

    @Column(name = "docker_container_name", length = 256)
    private String dockerContainerName;

    @Column(name = "docker_container_status", length = 128)
    private String dockerContainerStatus;

    @Column(name = "docker_ports", length = 512)
    private String dockerPorts;
}
