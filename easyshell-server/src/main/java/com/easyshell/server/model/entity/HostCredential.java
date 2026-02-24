package com.easyshell.server.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "host_credential")
public class HostCredential extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String ip;

    @Column(name = "ssh_port", nullable = false)
    private Integer sshPort = 22;

    @Column(name = "ssh_username", nullable = false, length = 64)
    private String sshUsername;

    /**
     * AES-256-CBC encrypted SSH password (Base64 encoded)
     */
    @Column(name = "ssh_password_encrypted", nullable = false, length = 512)
    private String sshPasswordEncrypted;

    /**
     * Associated agent ID after successful provisioning
     */
    @Column(name = "agent_id", length = 64)
    private String agentId;

    /**
     * PENDING, CONNECTING, UPLOADING, INSTALLING, STARTING, SUCCESS, FAILED
     */
    @Column(name = "provision_status", nullable = false, length = 20)
    private String provisionStatus = "PENDING";

    @Column(name = "provision_log", columnDefinition = "TEXT")
    private String provisionLog;

    @Column(name = "error_message", length = 500)
    private String errorMessage;
}
